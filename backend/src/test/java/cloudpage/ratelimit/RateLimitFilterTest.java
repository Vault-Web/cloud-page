package cloudpage.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

  private final AtomicLong clock = new AtomicLong(0L);
  private final LongSupplier nanoClock = clock::get;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private RateLimitFilter filterWith(RateLimitProperties properties) {
    RateLimitService service = new RateLimitService(properties, Optional.empty(), nanoClock);
    return new RateLimitFilter(service, properties);
  }

  private void authenticate(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  private MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @Test
  void allowsRequestUnderLimitAndSetsRemainingHeader() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(5, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request("POST", "/api/files/upload"), response, chain);

    assertEquals(200, response.getStatus());
    assertNotNull(chain.getRequest(), "chain should proceed when under the limit");
    assertEquals("4", response.getHeader("X-Rate-Limit-Remaining"));
  }

  @Test
  void blocksWithTooManyRequestsWhenBudgetExhausted() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    // first request consumes the only token
    filter.doFilter(
        request("POST", "/api/files/upload"), new MockHttpServletResponse(), new MockFilterChain());

    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request("POST", "/api/files/upload"), response, chain);

    assertEquals(429, response.getStatus());
    assertNull(chain.getRequest(), "chain must not proceed when throttled");
    assertNotNull(response.getHeader(HttpHeaders.RETRY_AFTER));
    assertEquals("0", response.getHeader("X-Rate-Limit-Remaining"));
    assertTrue(response.getContentAsString().contains("Too Many Requests"));
  }

  @Test
  void uploadAndListingHaveSeparateBudgets() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    properties.getPerClient().setListing(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    filter.doFilter(
        request("POST", "/api/files/upload"), new MockHttpServletResponse(), new MockFilterChain());

    // listing is a different category, so it still has its own token
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request("GET", "/api/folders/content"), response, chain);

    assertEquals(200, response.getStatus());
    assertNotNull(chain.getRequest());
  }

  @Test
  void unmatchedPathsAreNotLimited() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    // an endpoint outside the upload/download/listing set always passes through
    for (int i = 0; i < 5; i++) {
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(request("GET", "/api/auth/me"), new MockHttpServletResponse(), chain);
      assertNotNull(chain.getRequest());
    }
  }

  @Test
  void disabledFilterPassesEverythingThrough() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setEnabled(false);
    properties.getPerClient().setUpload(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    for (int i = 0; i < 5; i++) {
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(request("POST", "/api/files/upload"), new MockHttpServletResponse(), chain);
      assertNotNull(chain.getRequest(), "disabled filter must not block");
    }
  }

  @Test
  void unauthenticatedRequestsAreKeyedByIp() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setListing(new RateLimitProperties.Policy(1, Duration.ofMinutes(1)));
    RateLimitFilter filter = filterWith(properties);
    // no authentication in the context -> IP keying

    MockHttpServletRequest first = request("GET", "/api/folders");
    first.setRemoteAddr("10.0.0.1");
    filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

    // same IP, second request exceeds the budget
    MockHttpServletRequest second = request("GET", "/api/folders");
    second.setRemoteAddr("10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(second, response, new MockFilterChain());
    assertEquals(429, response.getStatus());

    // a different IP has its own budget
    MockHttpServletRequest other = request("GET", "/api/folders");
    other.setRemoteAddr("10.0.0.2");
    MockHttpServletResponse otherResponse = new MockHttpServletResponse();
    MockFilterChain otherChain = new MockFilterChain();
    filter.doFilter(other, otherResponse, otherChain);
    assertEquals(200, otherResponse.getStatus());
    assertNotNull(otherChain.getRequest());
  }

  @Test
  void scanStartIsLimitedButStatusPollingIsNot() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setScan(new RateLimitProperties.Policy(1, Duration.ofMinutes(5)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    filter.doFilter(
        request("POST", "/api/files/scan"), new MockHttpServletResponse(), new MockFilterChain());

    MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
    MockFilterChain limitedChain = new MockFilterChain();
    filter.doFilter(request("POST", "/api/files/scan"), limitedResponse, limitedChain);

    assertEquals(429, limitedResponse.getStatus());
    assertNull(limitedChain.getRequest());
    assertTrue(limitedResponse.getContentAsString().contains("\"category\":\"SCAN\""));
    assertNotNull(limitedResponse.getHeader(HttpHeaders.RETRY_AFTER));

    MockHttpServletResponse pollingResponse = new MockHttpServletResponse();
    MockFilterChain pollingChain = new MockFilterChain();
    filter.doFilter(request("GET", "/api/files/scan/job-1"), pollingResponse, pollingChain);

    assertEquals(200, pollingResponse.getStatus());
    assertNotNull(pollingChain.getRequest());
  }

  @Test
  void nonPositiveScanCapacityDisablesScanLimit() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getPerClient().setScan(new RateLimitProperties.Policy(0, Duration.ofMinutes(5)));
    RateLimitFilter filter = filterWith(properties);
    authenticate("alice");

    for (int i = 0; i < 5; i++) {
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(request("POST", "/api/files/scan"), new MockHttpServletResponse(), chain);
      assertNotNull(chain.getRequest());
    }
  }
}
