package cloudpage.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.model.User;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.UrlResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileController.class)
@AutoConfigureMockMvc(addFilters = false)
class FileControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileService fileService;
  @MockitoBean private UserService userService;
  @MockitoBean private FolderService folderService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;

  @TempDir Path tempDir;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser = new User();
    testUser.setId("user-1");
    testUser.setUsername("testuser");
    testUser.setRootFolderPath(tempDir.toString());
    when(userService.getCurrentUser()).thenReturn(testUser);
  }

  // ── POST /api/files/upload ───────────────────────────────────────────────

  @Test
  void uploadFile_validRequest_returns200() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

    mockMvc
        .perform(multipart("/api/files/upload").file(file).param("folderPath", "docs"))
        .andExpect(status().isOk());

    verify(fileService).uploadFile(eq(tempDir.toString()), eq("docs"), any());
  }

  @Test
  void uploadFile_missingFile_returns400() throws Exception {
    mockMvc
        .perform(multipart("/api/files/upload").param("folderPath", "docs"))
        .andExpect(status().isBadRequest());
  }

  // ── GET /api/files/content ───────────────────────────────────────────────

  @Test
  void getFileContent_existingFile_returnsContent() throws Exception {
    Path file = Files.writeString(tempDir.resolve("hello.txt"), "Hello World");
    when(fileService.readFileContent(tempDir.toString(), "hello.txt")).thenReturn("Hello World");

    mockMvc
        .perform(get("/api/files/content").param("path", "hello.txt"))
        .andExpect(status().isOk())
        .andExpect(content().string("Hello World"));
  }

  @Test
  void getFileContent_nonExistentFile_returns404() throws Exception {
    mockMvc
        .perform(get("/api/files/content").param("path", "ghost.txt"))
        .andExpect(status().isNotFound());
  }

  // ── DELETE /api/files ────────────────────────────────────────────────────

  @Test
  void deleteFile_validRequest_returns200() throws Exception {
    mockMvc.perform(delete("/api/files").param("filePath", "old.txt")).andExpect(status().isOk());

    verify(fileService).deleteFile(tempDir.toString(), "old.txt");
  }

  // ── PATCH /api/files/move ────────────────────────────────────────────────

  @Test
  void renameOrMoveFile_validRequest_returns200() throws Exception {
    mockMvc
        .perform(
            patch("/api/files/move").param("filePath", "old.txt").param("newPath", "renamed.txt"))
        .andExpect(status().isOk());

    verify(fileService).renameOrMoveFile(tempDir.toString(), "old.txt", "renamed.txt");
  }

  // ── GET /api/files/download ──────────────────────────────────────────────

  @Test
  void downloadFile_existingFile_returnsResourceWithHeaders() throws Exception {
    Path file = Files.writeString(tempDir.resolve("download.txt"), "data");
    FileResource fileResource =
        new FileResource(new UrlResource(file.toUri()), "\"4-123456\"", 123456L);
    when(fileService.loadAsResource(any(Path.class))).thenReturn(fileResource);

    mockMvc
        .perform(get("/api/files/download").param("path", "download.txt"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"download.txt\""))
        .andExpect(header().exists("ETag"));
  }

  @Test
  void downloadFile_nonExistentFile_returns404() throws Exception {
    when(fileService.loadAsResource(any(Path.class)))
        .thenThrow(new FileNotFoundException("File not found"));

    mockMvc
        .perform(get("/api/files/download").param("path", "missing.txt"))
        .andExpect(status().isNotFound());
  }

  // ── GET /api/files/view ──────────────────────────────────────────────────

  @Test
  void viewFile_existingFile_returnsResourceWithContentType() throws Exception {
    Path file = Files.writeString(tempDir.resolve("view.txt"), "viewme");

    mockMvc
        .perform(get("/api/files/view").param("path", "view.txt"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Content-Type"));
  }

  @Test
  void viewFile_nonExistentFile_returns404() throws Exception {
    mockMvc
        .perform(get("/api/files/view").param("path", "nofile.txt"))
        .andExpect(status().isNotFound());
  }
}
