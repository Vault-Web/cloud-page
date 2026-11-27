package cloudpage.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.dto.NotificationDto;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.NotificationService;
import cloudpage.service.UserService;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private NotificationService notificationService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;
  @MockitoBean private RateLimitFilter rateLimitFilter;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser = new User();
    testUser.setId("user-1");
    testUser.setUsername("testuser");
    testUser.setRootFolderPath("C:/fake/path");
    when(userService.getCurrentUser()).thenReturn(testUser);
  }

  @Test
  void listNotifications_returns200() throws Exception {
    NotificationDto n =
        new NotificationDto(
            1L, "testuser", "MENTION", "message", "doc.pdf", "owner", false, Instant.now());

    when(notificationService.getNotificationsForUser("testuser", false))
        .thenReturn(Collections.singletonList(n));

    mockMvc
        .perform(get("/api/notifications").param("unreadOnly", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1L))
        .andExpect(jsonPath("$[0].message").value("message"));
  }

  @Test
  void markAsRead_returns200() throws Exception {
    mockMvc.perform(post("/api/notifications/1/read")).andExpect(status().isOk());

    verify(notificationService).markAsRead("testuser", 1L);
  }

  @Test
  void markAllAsRead_returns200() throws Exception {
    mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isOk());

    verify(notificationService).markAllAsRead("testuser");
  }
}
