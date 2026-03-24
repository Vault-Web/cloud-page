package cloudpage.controller;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

  private final FileService fileService;
  private final UserService userService;
  private final FolderService folderService;

  @PostMapping("/upload")
  public void uploadFile(@RequestParam String folderPath, @RequestParam MultipartFile file)
      throws IOException {
    var user = userService.getCurrentUser();
    fileService.uploadFile(user.getRootFolderPath(), folderPath, file);
  }

  @GetMapping("/content")
  public ResponseEntity<String> getFileContent(@RequestParam String path) throws IOException {
    var user = userService.getCurrentUser();
    Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
    folderService.validatePath(user.getRootFolderPath(), fullPath);

    if (!fullPath.toFile().exists() || !fullPath.toFile().isFile()) {
      throw new FileNotFoundException("File Not Found with path : " + path);
    }

    String content = fileService.readFileContent(user.getRootFolderPath(), path);
    return ResponseEntity.ok(content);
  }

  @DeleteMapping
  public void deleteFile(@RequestParam String filePath) throws IOException {
    var user = userService.getCurrentUser();
    fileService.deleteFile(user.getRootFolderPath(), filePath);
  }

  @PatchMapping("/move")
  public void renameOrMoveFile(@RequestParam String filePath, @RequestParam String newPath)
      throws IOException {
    var user = userService.getCurrentUser();
    fileService.renameOrMoveFile(user.getRootFolderPath(), filePath, newPath);
  }

  @GetMapping("/download")
  public ResponseEntity<Resource> downloadFile(@RequestParam String path) throws IOException {
    var user = userService.getCurrentUser();
    Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
    folderService.validatePath(user.getRootFolderPath(), fullPath); // ensure security

    FileResource result = fileService.loadAsResource(fullPath);

    return ResponseEntity.ok()
        .eTag(result.getETag())
        .lastModified(result.getLastModified())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + fullPath.getFileName() + "\"")
        .body(result.getResource());
  }

  @GetMapping("/view")
  public ResponseEntity<Resource> viewFile(@RequestParam String path) throws IOException {
    var user = userService.getCurrentUser();
    Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
    folderService.validatePath(user.getRootFolderPath(), fullPath);

    if (!fullPath.toFile().exists() || !fullPath.toFile().isFile()) {
      throw new FileNotFoundException("File Not Found with path : " + path);
    }

    Resource resource = new UrlResource(fullPath.toUri());
    String mimeType = Files.probeContentType(fullPath);
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }

    return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, mimeType).body(resource);
  }
}
