package cloudpage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.dto.CommentDto;
import cloudpage.dto.CreateCommentRequest;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.CommentService;
import cloudpage.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommentController.class)
@AutoConfigureMockMvc(addFilters = false)
class CommentControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  @MockitoBean private CommentService commentService;
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
  void addComment_validRequest_returns200() throws Exception {
    CreateCommentRequest request = new CreateCommentRequest();
    request.setOwnerUsername("testuser");
    request.setFilePath("file.txt");
    request.setContent("This is a comment");

    CommentDto response =
        new CommentDto(1L, "testuser", "file.txt", "testuser", "This is a comment", Instant.now());

    when(commentService.addComment(eq(testUser), any(CreateCommentRequest.class)))
        .thenReturn(response);

    mockMvc
        .perform(
            post("/api/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1L))
        .andExpect(jsonPath("$.content").value("This is a comment"));
  }

  @Test
  void listComments_validRequest_returns200() throws Exception {
    CommentDto comment =
        new CommentDto(1L, "testuser", "file.txt", "testuser", "This is a comment", Instant.now());

    when(commentService.listComments(eq(testUser), eq("testuser"), eq("file.txt")))
        .thenReturn(Collections.singletonList(comment));

    mockMvc
        .perform(
            get("/api/comments").param("ownerUsername", "testuser").param("filePath", "file.txt"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1L))
        .andExpect(jsonPath("$[0].content").value("This is a comment"));
  }

  @Test
  void deleteComment_validRequest_returns200() throws Exception {
    mockMvc.perform(delete("/api/comments/1")).andExpect(status().isOk());

    verify(commentService).deleteComment(testUser, 1L);
  }
}
