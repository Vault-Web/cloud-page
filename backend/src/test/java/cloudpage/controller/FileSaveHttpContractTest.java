package cloudpage.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloudpage.exceptions.GlobalExceptionHandler;
import cloudpage.model.ResourceShare;
import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.ResourceShareRepository;
import cloudpage.repository.UserRepository;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.ResourceShareService;
import cloudpage.service.TrashService;
import cloudpage.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * File-save HTTP contract with real controllers, services and disk; mocked identity and
 * repositories.
 */
class FileSaveHttpContractTest {
  @TempDir Path root;
  private MockMvc mvc;
  private UserService users;
  private ResourceShareRepository shares;
  private ResourceShare share;
  private User owner;
  private User recipient;

  @BeforeEach
  void setup() throws Exception {
    users = mock(UserService.class);
    shares = mock(ResourceShareRepository.class);
    UserRepository repository = mock(UserRepository.class);
    owner = user("owner", root);
    recipient = user("recipient", root.resolve("recipient"));
    when(users.getCurrentUser()).thenReturn(owner);
    when(repository.findById("owner")).thenReturn(Optional.of(owner));
    Files.createDirectory(root.resolve("docs"));
    Files.createDirectory(root.resolve("docs/nested"));
    FileService files = new FileService();
    FolderService folders = new FolderService();
    TrashService trash = mock(TrashService.class);
    ResourceShareService shareService =
        new ResourceShareService(shares, repository, folders, files, trash);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new FileController(files, users, folders, trash),
                new ResourceShareController(shareService, users, folders))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    share = new ResourceShare();
    share.setId("s1");
    share.setOwnerId("owner");
    share.setRecipientId("recipient");
    share.setRelativePath("docs");
    share.setResourceType(SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.VIEW, SharePermission.EDIT));
    share.setCreatedAt(Instant.now());
    when(shares.findByIdAndRecipientIdAndRevokedAtIsNull("s1", "recipient"))
        .thenReturn(Optional.of(share));
  }

  @Test
  void createRejectDuplicateAndIntentionallyEditOwnFile() throws Exception {
    mvc.perform(multipart("/api/files/upload").file(file("first")).param("folderPath", "docs"))
        .andExpect(status().isOk());
    mvc.perform(multipart("/api/files/upload").file(file("duplicate")).param("folderPath", "docs"))
        .andExpect(status().isConflict());
    assertEquals("first", Files.readString(root.resolve("docs/notes.txt")));
    mvc.perform(
            multipart("/api/files/upload")
                .file(file("edited"))
                .param("folderPath", "docs")
                .header("If-Match", "*"))
        .andExpect(status().isOk());
    assertEquals("edited", Files.readString(root.resolve("docs/notes.txt")));
  }

  @Test
  void staleVersionReturns412WithoutReplacingTheCurrentBytes() throws Exception {
    Path target = Files.writeString(root.resolve("docs/notes.txt"), "first");
    FileService files = new FileService();
    String staleETag = files.loadAsResource(target).getETag();
    Files.writeString(target, "changed by another editor");
    mvc.perform(
            multipart("/api/files/upload")
                .file(file("edited"))
                .param("folderPath", "docs")
                .header("If-Match", staleETag))
        .andExpect(status().isPreconditionFailed());
    assertEquals("changed by another editor", Files.readString(target));
    String currentETag = files.loadAsResource(target).getETag();
    mvc.perform(
            multipart("/api/files/upload")
                .file(file("edited"))
                .param("folderPath", "docs")
                .header("If-Match", currentETag))
        .andExpect(status().isOk());
    assertEquals("edited", Files.readString(target));
  }

  @Test
  void deletedOwnTargetIsNotRecreatedByEdit() throws Exception {
    mvc.perform(
            multipart("/api/files/upload")
                .file(file("edited"))
                .param("folderPath", "docs")
                .header("If-Match", "*"))
        .andExpect(status().isPreconditionFailed());
    assertEquals(false, Files.exists(root.resolve("docs/notes.txt")));
  }

  @Test
  void shareUploadRejectsDuplicateAndEditUpdatesOriginal() throws Exception {
    when(users.getCurrentUser()).thenReturn(recipient);
    mvc.perform(multipart("/api/shares/s1/upload").file(file("first")).param("path", "nested"))
        .andExpect(status().isNoContent());
    mvc.perform(multipart("/api/shares/s1/upload").file(file("duplicate")).param("path", "nested"))
        .andExpect(status().isConflict());
    assertEquals("first", Files.readString(root.resolve("docs/nested/notes.txt")));
    mvc.perform(
            multipart(HttpMethod.PUT, "/api/shares/s1/edit")
                .file(file("edited"))
                .param("path", "nested/notes.txt")
                .header("If-Match", "*"))
        .andExpect(status().isNoContent());
    assertEquals("edited", Files.readString(root.resolve("docs/nested/notes.txt")));
  }

  @Test
  void singleFileShareAcceptsEmptyPath() throws Exception {
    when(users.getCurrentUser()).thenReturn(recipient);
    Files.writeString(root.resolve("docs/notes.txt"), "old");
    share.setResourceType(SharedResourceType.FILE);
    share.setRelativePath("docs/notes.txt");
    mvc.perform(
            multipart(HttpMethod.PUT, "/api/shares/s1/edit")
                .file(file("edited"))
                .param("path", "")
                .header("If-Match", "*"))
        .andExpect(status().isNoContent());
    assertEquals("edited", Files.readString(root.resolve("docs/notes.txt")));
  }

  @Test
  void readOnlyShareCannotEdit() throws Exception {
    when(users.getCurrentUser()).thenReturn(recipient);
    Files.writeString(root.resolve("docs/notes.txt"), "old");
    share.setPermissions(Set.of(SharePermission.VIEW));
    mvc.perform(
            multipart(HttpMethod.PUT, "/api/shares/s1/edit")
                .file(file("edited"))
                .param("path", "notes.txt")
                .header("If-Match", "*"))
        .andExpect(status().isForbidden());
    assertEquals("old", Files.readString(root.resolve("docs/notes.txt")));
  }

  @Test
  void revokedShareCannotEdit() throws Exception {
    when(users.getCurrentUser()).thenReturn(recipient);
    Files.writeString(root.resolve("docs/notes.txt"), "old");
    when(shares.findByIdAndRecipientIdAndRevokedAtIsNull("s1", "recipient"))
        .thenReturn(Optional.empty());
    mvc.perform(
            multipart(HttpMethod.PUT, "/api/shares/s1/edit")
                .file(file("edited"))
                .param("path", "notes.txt")
                .header("If-Match", "*"))
        .andExpect(status().isNotFound());
    assertEquals("old", Files.readString(root.resolve("docs/notes.txt")));
  }

  private static MockMultipartFile file(String body) {
    return new MockMultipartFile(
        "file", "notes.txt", "text/plain", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static User user(String id, Path root) {
    User user = new User();
    user.setId(id);
    user.setUsername(id);
    user.setRootFolderPath(root.toString());
    return user;
  }
}
