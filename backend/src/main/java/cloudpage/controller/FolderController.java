package cloudpage.controller;

import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.FolderDto;
import cloudpage.dto.PageResponseDto;
import cloudpage.dto.SearchResult;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/folders")
public class FolderController {

  private final FolderService folderService;
  private final UserService userService;

  @GetMapping
  public FolderDto getUserRootFolder(
      @RequestParam(required = false, defaultValue = "false") boolean includeChildCounts)
      throws IOException {
    var user = userService.getCurrentUser();
    return folderService.getFolderTree(user.getRootFolderPath(), includeChildCounts);
  }

  @GetMapping("/content")
  public PageResponseDto<FolderContentItemDto> getFolderContent(
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestParam int page,
      @RequestParam int size,
      @RequestParam(required = false) String sort)
      throws IOException {
    if (page < 0) {
      throw new IllegalArgumentException("page must be greater than or equal to 0");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("size must be greater than 0");
    }

    var user = userService.getCurrentUser();
    return folderService.getFolderContentPage(user.getRootFolderPath(), path, page, size, sort);
  }

  @GetMapping("/path")
  public FolderDto getFolderByPath(
      @RequestParam String path,
      @RequestParam(required = false, defaultValue = "false") boolean includeChildCounts)
      throws IOException {
    var user = userService.getCurrentUser();
    return folderService.getFolderTree(user.getRootFolderPath(), path, includeChildCounts);
  }

  @PostMapping
  public FolderDto createFolder(
      @RequestParam String parentPath,
      @RequestParam String name,
      @RequestParam(required = false, defaultValue = "false") boolean includeChildCounts)
      throws IOException {
    var user = userService.getCurrentUser();
    folderService.createFolder(user.getRootFolderPath(), parentPath, name);
    return folderService.getFolderTree(user.getRootFolderPath(), includeChildCounts);
  }

  @DeleteMapping
  public FolderDto deleteFolder(
      @RequestParam String folderPath,
      @RequestParam(required = false, defaultValue = "false") boolean includeChildCounts)
      throws IOException {
    var user = userService.getCurrentUser();
    folderService.deleteFolder(user.getRootFolderPath(), folderPath);
    return folderService.getFolderTree(user.getRootFolderPath(), includeChildCounts);
  }

  @PatchMapping
  public FolderDto renameOrMoveFolder(
      @RequestParam String folderPath,
      @RequestParam String newPath,
      @RequestParam(required = false, defaultValue = "false") boolean includeChildCounts)
      throws IOException {
    var user = userService.getCurrentUser();
    folderService.renameOrMoveFolder(user.getRootFolderPath(), folderPath, newPath);
    return folderService.getFolderTree(user.getRootFolderPath(), includeChildCounts);
  }

  @GetMapping("/search")
  public ResponseEntity<List<SearchResult>> searchInFolder(
      @RequestParam String folderPath,
      @RequestParam String query,
      @RequestParam(defaultValue = "20") int maxResults,
      @RequestParam(defaultValue = "60") int minScore)
      throws IOException {
    if (query == null || query.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    if (maxResults < 0) {
      return ResponseEntity.badRequest().build();
    }
    int validatedMinScore = Math.max(0, Math.min(100, minScore));
    var user = userService.getCurrentUser();
    return ResponseEntity.ok(
        folderService.searchInFolder(
            user.getRootFolderPath(), folderPath, query, maxResults, validatedMinScore));
  }
}
