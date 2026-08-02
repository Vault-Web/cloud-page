package cloudpage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.SecureSendDto;
import cloudpage.model.SecureSend;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.FolderService;
import cloudpage.service.SecureSendService;
import cloudpage.service.UserService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A share link has to point at the site that serves the landing page, which behind a reverse proxy
 * is neither this API's own address nor the path it is mounted on. Getting this wrong sends the
 * recipient to whatever service happens to own {@code /api/} out there, so the configured origin is
 * pinned here.
 */
@WebMvcTest(SecureSendController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "cloudpage.share.public-base-url=https://share.example.com/")
class SecureSendControllerPublicBaseUrlTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SecureSendService secureSendService;
  @MockitoBean private UserService userService;
  @MockitoBean private FolderService folderService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;
  @MockitoBean private RateLimitFilter rateLimitFilter;

  @BeforeEach
  void setUp() {
    User owner = new User();
    owner.setId("owner-1");
    when(userService.getCurrentUser()).thenReturn(owner);
  }

  @Test
  void shareUrlUsesTheConfiguredPublicOriginAndTheLandingPage() throws Exception {
    SecureSend send = new SecureSend();
    send.setId("send-1");
    send.setDisplayName("project");
    send.setCreatedAt(Instant.parse("2026-07-17T10:00:00Z"));
    send.setExpiresAt(Instant.parse("2099-07-18T10:00:00Z"));
    when(secureSendService.create(any(), any(), any(), any()))
        .thenReturn(new CreatedSecureSend(send, "raw-token"));
    when(secureSendService.toDto(any(), any()))
        .thenAnswer(
            invocation ->
                new SecureSendDto(
                    "send-1",
                    invocation.getArgument(1),
                    "project",
                    SharedResourceType.FOLDER,
                    send.getCreatedAt(),
                    send.getExpiresAt(),
                    false,
                    false));

    mockMvc
        .perform(
            post("/api/secure-sends")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "filePath": "project",
                      "expiresAt": "2099-07-18T10:00:00Z"
                    }
                    """))
        .andExpect(status().isOk())
        // The landing page, not the API path: a trailing slash on the configured
        // origin must not produce a double slash either.
        .andExpect(jsonPath("$.url").value("https://share.example.com/s/raw-token"));
  }
}
