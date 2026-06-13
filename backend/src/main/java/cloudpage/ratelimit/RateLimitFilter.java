package cloudpage.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-user / per-IP (and optional instance-wide) rate limits to the file upload, download
 * and listing endpoints.
 *
 * <p>Runs right after {@link cloudpage.security.JwtAuthFilter} so the authenticated principal, when
 * present, is used as the bucket key (the client IP is used as a fallback). Requests that exceed
 * their budget are rejected with {@code 429 Too Many Requests} and a {@code Retry-After} header
 * before they reach the controllers.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String REMAINING_HEADER = "X-Rate-Limit-Remaining";

  private final RateLimitService rateLimitService;
  private final RateLimitProperties properties;

  public RateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties) {
    this.rateLimitService = rateLimitService;
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }

    RateLimitCategory category = categoryFor(request);
    if (category == null) {
      filterChain.doFilter(request, response);
      return;
    }

    String clientKey = clientKey(request);
    RateLimitDecision decision = rateLimitService.tryAcquire(category, clientKey);
    if (decision.allowed()) {
      response.setHeader(REMAINING_HEADER, Long.toString(decision.remainingTokens()));
      filterChain.doFilter(request, response);
      return;
    }

    log.warn(
        "Rate limit exceeded: category={}, client={}, retryAfter={}s",
        category,
        clientKey,
        decision.retryAfterSeconds());
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
    response.setHeader(REMAINING_HEADER, "0");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            String.format(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"category\":\"%s\","
                    + "\"retryAfterSeconds\":%d}",
                category.name(), decision.retryAfterSeconds()));
  }

  /** Maps a request to a rate-limit category, or {@code null} when it should not be limited. */
  private RateLimitCategory categoryFor(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getServletPath();
    if ("POST".equals(method) && "/api/files/upload".equals(path)) {
      return RateLimitCategory.UPLOAD;
    }
    if ("GET".equals(method)
        && ("/api/files/download".equals(path)
            || "/api/files/view".equals(path)
            || "/api/files/content".equals(path))) {
      return RateLimitCategory.DOWNLOAD;
    }
    if ("GET".equals(method) && path.startsWith("/api/folders")) {
      return RateLimitCategory.LISTING;
    }
    return null;
  }

  /** Authenticated username when available, otherwise the client IP. */
  private String clientKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && StringUtils.hasText(authentication.getName())
        && !"anonymousUser".equals(authentication.getName())) {
      return "user:" + authentication.getName();
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    String ip =
        StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    return "ip:" + ip;
  }
}
