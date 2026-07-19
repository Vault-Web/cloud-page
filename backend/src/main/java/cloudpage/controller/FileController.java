package cloudpage.controller;

import cloudpage.dto.ChecksumDto;
import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.TrashService;
import cloudpage.service.UserService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
  private final TrashService trashService;

  @PostMapping("/upload")
  public void uploadFile(@RequestParam String folderPath, @RequestParam MultipartFile file)
      throws IOException {
    var user = userService.getCurrentUser();
    fileService.uploadFile(user.getRootFolderPath(), folderPath, file, user.getStorageQuotaMb());
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
    trashService.moveToTrash(user.getRootFolderPath(), user.getId(), filePath);
  }

  @DeleteMapping("/bulk")
  public ResponseEntity<java.util.Map<String, String>> deleteFiles(
      @RequestParam java.util.List<String> filePaths) {
    var user = userService.getCurrentUser();
    java.util.Map<String, String> results = new java.util.LinkedHashMap<>();
    for (String filePath : filePaths) {
      try {
        trashService.moveToTrash(user.getRootFolderPath(), user.getId(), filePath);
        results.put(filePath, "SUCCESS");
      } catch (Exception e) {
        results.put(filePath, "FAILED: " + e.getMessage());
      }
    }
    return ResponseEntity.ok(results);
  }

  @GetMapping("/download")
  public ResponseEntity<Resource> downloadFile(@RequestParam String path) throws IOException {
    var user = userService.getCurrentUser();
    Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
    folderService.validatePath(user.getRootFolderPath(), fullPath); // ensure security

    FileResource result = fileService.loadAsResource(fullPath);
    String mimeType = Files.probeContentType(fullPath);
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }

    return ResponseEntity.ok()
        .eTag(result.getETag())
        .lastModified(result.getLastModified())
        .header(HttpHeaders.CONTENT_TYPE, mimeType)
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

    FileResource result = fileService.loadAsResource(fullPath);
    String mimeType = Files.probeContentType(fullPath);
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }

    return ResponseEntity.ok()
        .eTag(result.getETag())
        .lastModified(result.getLastModified())
        .contentType(MediaType.parseMediaType(mimeType))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(fullPath.getFileName().toString(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(result.getResource());
  }

  @GetMapping("/checksum")
  public ResponseEntity<ChecksumDto> getFileChecksum(
      @RequestParam String path, @RequestParam(required = false) String expected)
      throws IOException {
    var user = userService.getCurrentUser();
    String checksum = fileService.calculateChecksum(user.getRootFolderPath(), path);
    Boolean match = (expected == null) ? null : checksum.equalsIgnoreCase(expected);
    return ResponseEntity.ok(new ChecksumDto(FileService.CHECKSUM_ALGORITHM, checksum, match));
  }

  @PatchMapping("/bulk/move")
  public ResponseEntity<java.util.Map<String, String>> moveFiles(
      @RequestParam java.util.List<String> filePaths, @RequestParam String targetPath) {
    var user = userService.getCurrentUser();
    java.util.Map<String, String> results = new java.util.LinkedHashMap<>();
    for (String filePath : filePaths) {
      try {
        fileService.renameOrMoveFile(
            user.getRootFolderPath(),
            filePath,
            targetPath + "/" + new java.io.File(filePath).getName());
        results.put(filePath, "SUCCESS");
      } catch (Exception e) {
        results.put(filePath, "FAILED: " + e.getMessage());
      }
    }
    return ResponseEntity.ok(results);
  }
}
