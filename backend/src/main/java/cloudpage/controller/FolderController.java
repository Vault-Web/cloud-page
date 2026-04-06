package cloudpage.controller;

import cloudpage.dto.FolderDto;
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
  public FolderDto getUserRootFolder() throws IOException {
    var user = userService.getCurrentUser();
    return folderService.getFolderTree(user.getRootFolderPath());
  }

  @GetMapping("/path")
  public FolderDto getFolderByPath(@RequestParam String path) throws IOException {
    var user = userService.getCurrentUser();
    return folderService.getFolderTree(user.getRootFolderPath(), path);
  }

  @PostMapping
  public FolderDto createFolder(@RequestParam String parentPath, @RequestParam String name)
      throws IOException {
    var user = userService.getCurrentUser();
    folderService.createFolder(user.getRootFolderPath(), parentPath, name);
    return folderService.getFolderTree(user.getRootFolderPath());
  }

  @DeleteMapping
  public FolderDto deleteFolder(@RequestParam String folderPath) throws IOException {
    var user = userService.getCurrentUser();
    folderService.deleteFolder(user.getRootFolderPath(), folderPath);
    return folderService.getFolderTree(user.getRootFolderPath());
  }

  @PatchMapping
  public FolderDto renameOrMoveFolder(@RequestParam String folderPath, @RequestParam String newPath)
      throws IOException {
    var user = userService.getCurrentUser();
    folderService.renameOrMoveFolder(user.getRootFolderPath(), folderPath, newPath);
    return folderService.getFolderTree(user.getRootFolderPath());
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
