package cloudpage.controller;

import cloudpage.dto.CreateSecureSendRequest;
import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.SecureSendDto;
import cloudpage.dto.SecureSendResource;
import cloudpage.service.SecureSendService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SecureSendController {

  public static final String PASSWORD_HEADER = "X-Secure-Send-Password";

  private final SecureSendService secureSendService;
  private final UserService userService;

  @PostMapping("/secure-sends")
  public ResponseEntity<SecureSendDto> create(@Valid @RequestBody CreateSecureSendRequest request)
      throws IOException {
    CreatedSecureSend created =
        secureSendService.create(
            userService.getCurrentUser(),
            request.getFilePath(),
            request.getExpiresAt(),
            request.getPassword());
    String url =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/public/secure-sends/{token}")
            .buildAndExpand(created.token())
            .toUriString();
    return ResponseEntity.ok(secureSendService.toDto(created.secureSend(), url));
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

  @GetMapping("/public/secure-sends/{token}")
  public ResponseEntity<Resource> download(
      @PathVariable String token,
      @RequestHeader(value = PASSWORD_HEADER, required = false) String password)
      throws IOException {
    SecureSendResource result = secureSendService.resolve(token, password);
    String mimeType = Files.probeContentType(result.getPath());
    if (mimeType == null) {
      mimeType = "application/octet-stream";
    }
    String disposition =
        ContentDisposition.attachment()
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
