package cloudpage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.FileResource;
import cloudpage.dto.SecureSendDto;
import cloudpage.dto.SecureSendResource;
import cloudpage.exceptions.InvalidSecureSendPasswordException;
import cloudpage.exceptions.SecureSendUnavailableException;
import cloudpage.model.SecureSend;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.SecureSendService;
import cloudpage.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SecureSendController.class)
@AutoConfigureMockMvc(addFilters = false)
class SecureSendControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SecureSendService secureSendService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;
  @MockitoBean private RateLimitFilter rateLimitFilter;

  @TempDir Path tempDir;

  private User owner;

  @BeforeEach
  void setUp() {
    owner = new User();
    owner.setId("owner-1");
    when(userService.getCurrentUser()).thenReturn(owner);
  }

  @Test
  void createReturnsShareableUrlWithoutSecrets() throws Exception {
    SecureSend send = new SecureSend();
    send.setId("send-1");
    send.setDisplayName("report.pdf");
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
                    "report.pdf",
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
                      "filePath": "report.pdf",
                      "expiresAt": "2099-07-18T10:00:00Z"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("send-1"))
        .andExpect(jsonPath("$.url").value("http://localhost/api/public/secure-sends/raw-token"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("tokenHash"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("passwordHash"))));
  }

  @Test
  void listIsScopedToCurrentOwner() throws Exception {
    when(secureSendService.list("owner-1")).thenReturn(List.of());

    mockMvc.perform(get("/api/secure-sends")).andExpect(status().isOk());

    verify(secureSendService).list("owner-1");
  }

  @Test
  void revokeReturnsNoContent() throws Exception {
    mockMvc.perform(delete("/api/secure-sends/send-1")).andExpect(status().isNoContent());

    verify(secureSendService).revoke("owner-1", "send-1");
  }

  @Test
  void publicDownloadReturnsResourceHeaders() throws Exception {
    Path file = Files.writeString(tempDir.resolve("report.txt"), "report");
    FileResource fileResource = new FileResource(new UrlResource(file.toUri()), "\"6-123\"", 123L);
    when(secureSendService.resolve("token", "secret"))
        .thenReturn(new SecureSendResource(file, fileResource));

    mockMvc
        .perform(
            get("/api/public/secure-sends/token")
                .header(SecureSendController.PASSWORD_HEADER, "secret"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"6-123\""))
        .andExpect(header().exists("Content-Disposition"));
  }

  @Test
  void unavailablePublicLinkReturns404() throws Exception {
    when(secureSendService.resolve("missing", null))
        .thenThrow(new SecureSendUnavailableException());

    mockMvc
        .perform(get("/api/public/secure-sends/missing"))
        .andExpect(status().isNotFound())
        .andExpect(content().string("Secure Send link is unavailable"));
  }

  @Test
  void incorrectPasswordReturns401() throws Exception {
    when(secureSendService.resolve("token", "wrong"))
        .thenThrow(new InvalidSecureSendPasswordException());

    mockMvc
        .perform(
            get("/api/public/secure-sends/token")
                .header(SecureSendController.PASSWORD_HEADER, "wrong"))
        .andExpect(status().isUnauthorized());
  }
}
