package cloudpage.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.FolderDto;
import cloudpage.dto.PageResponseDto;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.model.User;
import cloudpage.security.JwtAuthFilter;
import cloudpage.security.JwtUtil;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FolderController.class)
@AutoConfigureMockMvc(addFilters = false)
class FolderControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FolderService folderService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtAuthFilter jwtAuthFilter;
  @MockitoBean private JwtUtil jwtUtil;

  @TempDir Path tempDir;

  private User testUser;
  private FolderDto rootFolder;

  @BeforeEach
  void setUp() {
    testUser = new User();
    testUser.setId("user-1");
    testUser.setUsername("testuser");
    testUser.setRootFolderPath(tempDir.toString());
    when(userService.getCurrentUser()).thenReturn(testUser);

    rootFolder =
        new FolderDto("root", tempDir.toString(), Collections.emptyList(), Collections.emptyList());
  }

  // ── GET /api/folders ─────────────────────────────────────────────────────

  @Test
  void getUserRootFolder_returnsRootFolderDto() throws Exception {
    when(folderService.getFolderTree(tempDir.toString())).thenReturn(rootFolder);

    mockMvc
        .perform(get("/api/folders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("root"))
        .andExpect(jsonPath("$.folders").isArray())
        .andExpect(jsonPath("$.files").isArray());
  }

  // ── GET /api/folders/path ────────────────────────────────────────────────

  @Test
  void getFolderByPath_validPath_returnsSubfolderDto() throws Exception {
    FolderDto subFolder =
        new FolderDto(
            "docs",
            tempDir.resolve("docs").toString(),
            Collections.emptyList(),
            Collections.emptyList());
    when(folderService.getFolderTree(tempDir.toString(), "docs")).thenReturn(subFolder);

    mockMvc
        .perform(get("/api/folders/path").param("path", "docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("docs"));
  }

  @Test
  void getFolderByPath_invalidPath_returns400() throws Exception {
    when(folderService.getFolderTree(tempDir.toString(), "../../evil"))
        .thenThrow(new InvalidPathException("Access outside the user's root folder is forbidden"));

    mockMvc
        .perform(get("/api/folders/path").param("path", "../../evil"))
        .andExpect(status().isBadRequest());
  }

  // ── POST /api/folders ────────────────────────────────────────────────────

  @Test
  void createFolder_validRequest_returnsUpdatedTree() throws Exception {
    when(folderService.createFolder(tempDir.toString(), "docs", "newDir"))
        .thenReturn(tempDir.resolve("docs/newDir"));
    when(folderService.getFolderTree(tempDir.toString())).thenReturn(rootFolder);

    mockMvc
        .perform(post("/api/folders").param("parentPath", "docs").param("name", "newDir"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("root"));

    verify(folderService).createFolder(tempDir.toString(), "docs", "newDir");
  }

  @Test
  void createFolder_missingParams_returns400() throws Exception {
    mockMvc.perform(post("/api/folders")).andExpect(status().isBadRequest());
  }

  // ── DELETE /api/folders ──────────────────────────────────────────────────

  @Test
  void deleteFolder_validRequest_returnsUpdatedTree() throws Exception {
    when(folderService.getFolderTree(tempDir.toString())).thenReturn(rootFolder);

    mockMvc
        .perform(delete("/api/folders").param("folderPath", "oldDir"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("root"));

    verify(folderService).deleteFolder(tempDir.toString(), "oldDir");
  }

  // ── PATCH /api/folders ───────────────────────────────────────────────────

  @Test
  void renameOrMoveFolder_validRequest_returnsUpdatedTree() throws Exception {
    when(folderService.getFolderTree(tempDir.toString())).thenReturn(rootFolder);

    mockMvc
        .perform(patch("/api/folders").param("folderPath", "oldName").param("newPath", "newName"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("root"));

    verify(folderService).renameOrMoveFolder(tempDir.toString(), "oldName", "newName");
  }

  @Test
  void renameOrMoveFolder_pathTraversal_returns400() throws Exception {
    doThrow(new InvalidPathException("Forbidden"))
        .when(folderService)
        .renameOrMoveFolder(eq(tempDir.toString()), eq("safe"), eq("../../evil"));

    mockMvc
        .perform(patch("/api/folders").param("folderPath", "safe").param("newPath", "../../evil"))
        .andExpect(status().isBadRequest());
  }

  // ── GET /api/folders/content ───────────────────────────────────────────────

  @Test
  void getFolderContent_validRequest_returnsPageResponse() throws Exception {
    List<FolderContentItemDto> content =
        Arrays.asList(
            new FolderContentItemDto("file1.txt", "file1.txt", false, 100L, "text/plain"),
            new FolderContentItemDto("file2.txt", "file2.txt", false, 200L, "text/plain"));
    PageResponseDto<FolderContentItemDto> pageResponse = new PageResponseDto<>(content, 2L, 1, 0);

    when(folderService.getFolderContentPage(
            eq(tempDir.toString()), eq(""), eq(0), eq(10), eq(null)))
        .thenReturn(pageResponse);

    mockMvc
        .perform(get("/api/folders/content").param("page", "0").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.pageNumber").value(0))
        .andExpect(jsonPath("$.content[0].name").value("file1.txt"))
        .andExpect(jsonPath("$.content[1].name").value("file2.txt"));

    verify(folderService)
        .getFolderContentPage(eq(tempDir.toString()), eq(""), eq(0), eq(10), eq(null));
  }

  @Test
  void getFolderContent_withPath_returnsPageResponse() throws Exception {
    List<FolderContentItemDto> content =
        Collections.singletonList(
            new FolderContentItemDto("subfile.txt", "docs/subfile.txt", false, 50L, "text/plain"));
    PageResponseDto<FolderContentItemDto> pageResponse = new PageResponseDto<>(content, 1L, 1, 0);

    when(folderService.getFolderContentPage(
            eq(tempDir.toString()), eq("docs"), eq(0), eq(10), eq(null)))
        .thenReturn(pageResponse);

    mockMvc
        .perform(
            get("/api/folders/content")
                .param("path", "docs")
                .param("page", "0")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].name").value("subfile.txt"));

    verify(folderService)
        .getFolderContentPage(eq(tempDir.toString()), eq("docs"), eq(0), eq(10), eq(null));
  }

  @Test
  void getFolderContent_withSort_returnsSortedPageResponse() throws Exception {
    List<FolderContentItemDto> content =
        Arrays.asList(
            new FolderContentItemDto("apple.txt", "apple.txt", false, 100L, "text/plain"),
            new FolderContentItemDto("zebra.txt", "zebra.txt", false, 200L, "text/plain"));
    PageResponseDto<FolderContentItemDto> pageResponse = new PageResponseDto<>(content, 2L, 1, 0);

    when(folderService.getFolderContentPage(
            eq(tempDir.toString()), eq(""), eq(0), eq(10), eq("name,asc")))
        .thenReturn(pageResponse);

    mockMvc
        .perform(
            get("/api/folders/content")
                .param("page", "0")
                .param("size", "10")
                .param("sort", "name,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("apple.txt"))
        .andExpect(jsonPath("$.content[1].name").value("zebra.txt"));

    verify(folderService)
        .getFolderContentPage(eq(tempDir.toString()), eq(""), eq(0), eq(10), eq("name,asc"));
  }

  @Test
  void getFolderContent_negativePage_returns400() throws Exception {
    mockMvc
        .perform(get("/api/folders/content").param("page", "-1").param("size", "10"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_zeroSize_returns400() throws Exception {
    mockMvc
        .perform(get("/api/folders/content").param("page", "0").param("size", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_negativeSize_returns400() throws Exception {
    mockMvc
        .perform(get("/api/folders/content").param("page", "0").param("size", "-5"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_missingPageParam_returns400() throws Exception {
    mockMvc
        .perform(get("/api/folders/content").param("size", "10"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_missingSizeParam_returns400() throws Exception {
    mockMvc
        .perform(get("/api/folders/content").param("page", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_invalidPath_returns400() throws Exception {
    when(folderService.getFolderContentPage(
            eq(tempDir.toString()), eq("../../evil"), eq(0), eq(10), eq(null)))
        .thenThrow(new InvalidPathException("Path traversal attempt detected"));

    mockMvc
        .perform(
            get("/api/folders/content")
                .param("path", "../../evil")
                .param("page", "0")
                .param("size", "10"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getFolderContent_emptyFolder_returnsEmptyPage() throws Exception {
    PageResponseDto<FolderContentItemDto> emptyPage =
        new PageResponseDto<>(Collections.emptyList(), 0L, 0, 0);

    when(folderService.getFolderContentPage(
            eq(tempDir.toString()), eq(""), eq(0), eq(10), eq(null)))
        .thenReturn(emptyPage);

    mockMvc
        .perform(get("/api/folders/content").param("page", "0").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @Test
  void getFolderContent_secondPage_returnsCorrectPage() throws Exception {
    List<FolderContentItemDto> content =
        Collections.singletonList(
            new FolderContentItemDto("file3.txt", "file3.txt", false, 300L, "text/plain"));
    PageResponseDto<FolderContentItemDto> pageResponse = new PageResponseDto<>(content, 3L, 2, 1);

    when(folderService.getFolderContentPage(eq(tempDir.toString()), eq(""), eq(1), eq(2), eq(null)))
        .thenReturn(pageResponse);

    mockMvc
        .perform(get("/api/folders/content").param("page", "1").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.pageNumber").value(1));
  }
}
