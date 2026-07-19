package cloudpage.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.model.User;
import cloudpage.ratelimit.RateLimitFilter;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.TrashService;
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
  @MockitoBean private TrashService trashService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;
  @MockitoBean private RateLimitFilter rateLimitFilter;

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

    verify(fileService).uploadFile(eq(tempDir.toString()), eq("docs"), any(), any());
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
    Files.writeString(tempDir.resolve("hello.txt"), "Hello World");
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

    // delete now soft-deletes: the file is moved to the user's trash
    verify(trashService).moveToTrash(tempDir.toString(), "user-1", "old.txt");
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
    Path file = Files.writeString(tempDir.resolve("download.zip"), "data");
    FileResource fileResource =
        new FileResource(new UrlResource(file.toUri()), "\"4-123456\"", 123456L);
    when(fileService.loadAsResource(any(Path.class))).thenReturn(fileResource);

    mockMvc
        .perform(get("/api/files/download").param("path", "download.zip"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"download.zip\""))
        .andExpect(header().exists("Content-Type"))
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
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "view.txt"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/plain"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("filename*=UTF-8''view.txt")))
        .andExpect(header().string("ETag", org.hamcrest.Matchers.startsWith("\"6-")))
        .andExpect(header().exists("Last-Modified"))
        .andExpect(content().string("viewme"));
  }

  @Test
  void viewFile_rangeRequest_returnsPartialContent() throws Exception {
    Path file = Files.writeString(tempDir.resolve("video.mp4"), "0123456789");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "video.mp4").header("Range", "bytes=2-5"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 2-5/10"))
        .andExpect(content().bytes("2345".getBytes()));
  }

  @Test
  void viewFile_openRange_returnsRemainingContent() throws Exception {
    Path file = Files.writeString(tempDir.resolve("audio.mp3"), "0123456789");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "audio.mp3").header("Range", "bytes=6-"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 6-9/10"))
        .andExpect(content().bytes("6789".getBytes()));
  }

  @Test
  void viewFile_suffixRange_returnsLastBytes() throws Exception {
    Path file = Files.writeString(tempDir.resolve("document.pdf"), "0123456789");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "document.pdf").header("Range", "bytes=-4"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 6-9/10"))
        .andExpect(content().bytes("6789".getBytes()));
  }

  @Test
  void viewFile_withoutRange_returnsFullContent() throws Exception {
    Path file = Files.writeString(tempDir.resolve("image.png"), "0123456789");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "image.png"))
        .andExpect(status().isOk())
        .andExpect(content().bytes("0123456789".getBytes()));
  }

  @Test
  void viewFile_unsatisfiableRange_returns416() throws Exception {
    Path file = Files.writeString(tempDir.resolve("video.mp4"), "0123456789");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "video.mp4").header("Range", "bytes=20-30"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string("Content-Range", "bytes */10"));
  }

  @Test
  void viewFile_unknownMimeType_returnsOctetStream() throws Exception {
    Path file = Files.writeString(tempDir.resolve("archive.cloudpage-unknown"), "data");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "archive.cloudpage-unknown"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/octet-stream"));
  }

  @Test
  void viewFile_nonAsciiFilename_usesSafeInlineContentDisposition() throws Exception {
    String filename = "Urlaub Zürich 2026.pdf";
    Path file = Files.writeString(tempDir.resolve(filename), "data");
    when(fileService.loadAsResource(file)).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", filename))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''Urlaub%20Z%C3%BCrich%202026.pdf")));
  }

  @Test
  void viewFile_pathTraversal_returns400() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("root/nested"));
    Files.writeString(tempDir.resolve("outside.pdf"), "secret");
    testUser.setRootFolderPath(root.toString());
    doCallRealMethod().when(folderService).validatePath(eq(root.toString()), any(Path.class));

    mockMvc
        .perform(get("/api/files/view").param("path", "../../outside.pdf"))
        .andExpect(status().isBadRequest());

    verify(fileService, never()).loadAsResource(any(Path.class));
  }

  @Test
  void viewFile_symlinkOutsideUserRoot_returns400() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path outside = Files.writeString(tempDir.resolve("outside.pdf"), "secret");
    Path link = root.resolve("linked.pdf");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | SecurityException e) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links are not supported");
    } catch (java.nio.file.FileSystemException e) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links are not permitted");
    }
    testUser.setRootFolderPath(root.toString());
    doCallRealMethod().when(folderService).validatePath(eq(root.toString()), any(Path.class));

    mockMvc
        .perform(get("/api/files/view").param("path", "linked.pdf"))
        .andExpect(status().isBadRequest());

    verify(fileService, never()).loadAsResource(any(Path.class));
  }

  @Test
  void viewFile_nonExistentFile_returns404() throws Exception {
    when(fileService.loadAsResource(any(Path.class))).thenCallRealMethod();

    mockMvc
        .perform(get("/api/files/view").param("path", "nofile.txt"))
        .andExpect(status().isNotFound());
  }

  // ── GET /api/files/checksum ──────────────────────────────────────────────

  @Test
  void getFileChecksum_returnsAlgorithmAndChecksum() throws Exception {
    when(fileService.calculateChecksum(tempDir.toString(), "doc.pdf")).thenReturn("abc123");

    mockMvc
        .perform(get("/api/files/checksum").param("path", "doc.pdf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.algorithm").value("SHA-256"))
        .andExpect(jsonPath("$.checksum").value("abc123"))
        .andExpect(jsonPath("$.match").doesNotExist());
  }

  @Test
  void getFileChecksum_withMatchingExpected_returnsMatchTrue() throws Exception {
    when(fileService.calculateChecksum(tempDir.toString(), "doc.pdf")).thenReturn("ABC123");

    mockMvc
        .perform(get("/api/files/checksum").param("path", "doc.pdf").param("expected", "abc123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.match").value(true));
  }

  @Test
  void getFileChecksum_withWrongExpected_returnsMatchFalse() throws Exception {
    when(fileService.calculateChecksum(tempDir.toString(), "doc.pdf")).thenReturn("abc123");

    mockMvc
        .perform(get("/api/files/checksum").param("path", "doc.pdf").param("expected", "deadbeef"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.match").value(false));
  }

  @Test
  void getFileChecksum_fileNotFound_returns404() throws Exception {
    when(fileService.calculateChecksum(tempDir.toString(), "ghost.txt"))
        .thenThrow(new ResourceNotFoundException("File", "FilePath", "ghost.txt"));

    mockMvc
        .perform(get("/api/files/checksum").param("path", "ghost.txt"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getFileChecksum_pathTraversal_returns400() throws Exception {
    when(fileService.calculateChecksum(tempDir.toString(), "../../etc/passwd"))
        .thenThrow(new InvalidPathException("Path traversal attempt detected"));

    mockMvc
        .perform(get("/api/files/checksum").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest());
  }
}
