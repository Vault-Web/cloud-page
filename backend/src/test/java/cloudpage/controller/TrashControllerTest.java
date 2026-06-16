package cloudpage.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.dto.TrashEntryDto;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.TrashService;
import cloudpage.service.UserService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrashController.class)
@AutoConfigureMockMvc(addFilters = false)
class TrashControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TrashService trashService;
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
    testUser.setRootFolderPath("/root");
    when(userService.getCurrentUser()).thenReturn(testUser);
  }

  @Test
  void listTrash_returnsEntries() throws Exception {
    when(trashService.listTrash("user-1"))
        .thenReturn(List.of(new TrashEntryDto("t1", "doc.txt", "docs/doc.txt", Instant.now(), 4L)));

    mockMvc
        .perform(get("/api/files/trash"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("t1"))
        .andExpect(jsonPath("$[0].name").value("doc.txt"));
  }

  @Test
  void restore_invokesServiceWithCurrentUser() throws Exception {
    mockMvc.perform(post("/api/files/trash/t1/restore")).andExpect(status().isOk());

    verify(trashService).restore("/root", "user-1", "t1");
  }

  @Test
  void purge_invokesServiceWithCurrentUser() throws Exception {
    mockMvc.perform(delete("/api/files/trash/t1")).andExpect(status().isOk());

    verify(trashService).purge("/root", "user-1", "t1");
  }
}
