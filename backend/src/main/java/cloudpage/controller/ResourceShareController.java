package cloudpage.controller;

import cloudpage.dto.CreateResourceShareRequest;
import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.ResourceShareDto;
import cloudpage.dto.SharedFileResource;
import cloudpage.dto.SharedFolderResource;
import cloudpage.model.SharePermission;
import cloudpage.model.User;
import cloudpage.service.FolderService;
import cloudpage.service.ResourceShareService;
import cloudpage.service.UserService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/shares")
public class ResourceShareController {

  private final ResourceShareService shareService;
  private final UserService userService;
  private final FolderService folderService;

  @PostMapping
  public ResourceShareDto create(@Valid @RequestBody CreateResourceShareRequest request)
      throws IOException {
    return shareService.create(
        userService.getCurrentUser(),
        request.getPath(),
        request.getRecipientUsername(),
        request.getPermissions());
  }

  @GetMapping
  public List<ResourceShareDto> listOwned() {
    return shareService.listOwned(userService.getCurrentUser());
  }

  @GetMapping("/shared-with-me")
  public List<ResourceShareDto> listReceived() {
    return shareService.listReceived(userService.getCurrentUser());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> revoke(@PathVariable String id) {
    shareService.revoke(userService.getCurrentUser().getId(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/view")
  public ResponseEntity<Resource> view(
      @PathVariable String id, @RequestParam(required = false, defaultValue = "") String path)
      throws IOException {
    SharedFileResource result =
        shareService.resolveFile(id, userService.getCurrentUser(), path, SharePermission.VIEW);
    return fileResponse(result, false);
  }

  @GetMapping("/{id}/content")
  public List<FolderContentItemDto> content(
      @PathVariable String id, @RequestParam(required = false, defaultValue = "") String path)
      throws IOException {
    return shareService.listFolder(id, userService.getCurrentUser(), path);
  }

  @GetMapping("/{id}/download")
  public ResponseEntity<Resource> downloadFile(
      @PathVariable String id, @RequestParam(required = false, defaultValue = "") String path)
      throws IOException {
    SharedFileResource result =
        shareService.resolveFile(id, userService.getCurrentUser(), path, SharePermission.DOWNLOAD);
    return fileResponse(result, true);
  }

  @PutMapping("/{id}/edit")
  public ResponseEntity<Void> editFile(
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestParam MultipartFile file)
      throws IOException {
    shareService.editFile(id, userService.getCurrentUser(), path, file);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/download-folder")
  public ResponseEntity<StreamingResponseBody> downloadFolder(
      @PathVariable String id, @RequestParam(required = false, defaultValue = "") String path)
      throws IOException {
    User recipient = userService.getCurrentUser();
    SharedFolderResource result = shareService.resolveFolderDownload(id, recipient, path);
    String folderName = result.folder().getFileName().toString();
    StreamingResponseBody body =
        output ->
            folderService.writeFolderArchive(
                result.ownerRoot().toString(), result.folder(), output);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(folderName + ".zip", StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(body);
  }

  private ResponseEntity<Resource> fileResponse(SharedFileResource result, boolean attachment)
      throws IOException {
    String mimeType = Files.probeContentType(result.path());
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }
    ContentDisposition disposition =
        (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(result.path().getFileName().toString(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .eTag(result.fileResource().getETag())
        .lastModified(result.fileResource().getLastModified())
        .contentType(MediaType.parseMediaType(mimeType))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(result.fileResource().getResource());
  }
}
