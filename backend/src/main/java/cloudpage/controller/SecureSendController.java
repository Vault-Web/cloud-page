package cloudpage.controller;

import cloudpage.dto.CreateSecureSendRequest;
import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.PublicSecureSendDto;
import cloudpage.dto.SecureSendDto;
import cloudpage.dto.SecureSendResource;
import cloudpage.dto.SharedFolderResource;
import cloudpage.service.FolderService;
import cloudpage.service.SecureSendService;
import cloudpage.service.UserService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SecureSendController {

  public static final String PASSWORD_HEADER = "X-Secure-Send-Password";

  private static final String LANDING_PATH = "/s/{token}";

  private final SecureSendService secureSendService;
  private final UserService userService;
  private final FolderService folderService;

  /**
   * Public origin of the site that serves the share landing page, for example {@code
   * https://example.com}. A share link points at that page rather than at this API, so the
   * recipient can be told what they are about to download and be asked for a password. The incoming
   * request only carries the internal path behind the reverse proxy and cannot be used for this.
   * Left empty the origin is derived from the request, which is correct for local development.
   */
  @Value("${cloudpage.share.public-base-url:}")
  private String publicBaseUrl;

  @PostMapping("/secure-sends")
  public ResponseEntity<SecureSendDto> create(@Valid @RequestBody CreateSecureSendRequest request)
      throws IOException {
    CreatedSecureSend created =
        secureSendService.create(
            userService.getCurrentUser(),
            request.getFilePath(),
            request.getExpiresAt(),
            request.getPassword());
    return ResponseEntity.ok(
        secureSendService.toDto(created.secureSend(), buildShareUrl(created.token())));
  }

  private String buildShareUrl(String token) {
    UriComponentsBuilder base =
        publicBaseUrl == null || publicBaseUrl.isBlank()
            ? ServletUriComponentsBuilder.fromCurrentContextPath()
            : UriComponentsBuilder.fromUriString(publicBaseUrl.replaceAll("/+$", ""));
    return base.path(LANDING_PATH).buildAndExpand(token).toUriString();
  }

  @GetMapping("/secure-sends")
  public List<SecureSendDto> list() {
    return secureSendService.list(userService.getCurrentUser().getId());
  }

  @DeleteMapping("/secure-sends/{id}")
  public ResponseEntity<Void> revoke(@PathVariable String id) {
    secureSendService.revoke(userService.getCurrentUser().getId(), id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/secure-sends/{id}/permanent")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    secureSendService.delete(userService.getCurrentUser().getId(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/public/secure-sends/{token}/meta")
  public PublicSecureSendDto describe(@PathVariable String token) {
    return secureSendService.describe(token);
  }

  /**
   * Lists one level of a folder link so the recipient can navigate it. {@code path} is relative to
   * the shared folder; empty is the folder itself.
   */
  @GetMapping("/public/secure-sends/{token}/content")
  public List<FolderContentItemDto> content(
      @PathVariable String token,
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestHeader(value = PASSWORD_HEADER, required = false) String password)
      throws IOException {
    return secureSendService.listFolder(token, password, path);
  }

  @GetMapping("/public/secure-sends/{token}")
  public ResponseEntity<Resource> download(
      @PathVariable String token,
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestHeader(value = PASSWORD_HEADER, required = false) String password)
      throws IOException {
    return fileResponse(secureSendService.resolve(token, password, path), true);
  }

  /** Serves a file inline so the landing page can preview it instead of downloading it. */
  @GetMapping("/public/secure-sends/{token}/view")
  public ResponseEntity<Resource> view(
      @PathVariable String token,
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestHeader(value = PASSWORD_HEADER, required = false) String password)
      throws IOException {
    return fileResponse(secureSendService.resolve(token, password, path), false);
  }

  /** Streams a folder link, or one folder inside it, as a ZIP archive. */
  @GetMapping("/public/secure-sends/{token}/download-folder")
  public ResponseEntity<StreamingResponseBody> downloadFolder(
      @PathVariable String token,
      @RequestParam(required = false, defaultValue = "") String path,
      @RequestHeader(value = PASSWORD_HEADER, required = false) String password) {
    SharedFolderResource result = secureSendService.resolveFolderArchive(token, password, path);
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

  private ResponseEntity<Resource> fileResponse(SecureSendResource result, boolean attachment)
      throws IOException {
    String mimeType = Files.probeContentType(result.getPath());
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }
    String disposition =
        (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(result.getPath().getFileName().toString(), StandardCharsets.UTF_8)
            .build()
            .toString();

    return ResponseEntity.ok()
        .eTag(result.getFileResource().getETag())
        .lastModified(result.getFileResource().getLastModified())
        .header(HttpHeaders.CONTENT_TYPE, mimeType)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .body(result.getFileResource().getResource());
  }
}
