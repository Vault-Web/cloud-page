package cloudpage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloudpage.dto.CommentDto;
import cloudpage.dto.CreateCommentRequest;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.UnauthorizedAccessException;
import cloudpage.model.Comment;
import cloudpage.model.User;
import cloudpage.repository.CommentRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

  @Mock private CommentRepository commentRepository;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private FolderService folderService;

  @TempDir Path tempDir;

  private CommentService service;
  private User alice;
  private User bob;

  @BeforeEach
  void setUp() {
    service =
        new CommentService(commentRepository, userRepository, notificationService, folderService);

    alice = new User();
    alice.setId("user-alice");
    alice.setUsername("alice");
    alice.setRootFolderPath(tempDir.toString());

    bob = new User();
    bob.setId("user-bob");
    bob.setUsername("bob");
    bob.setRootFolderPath(tempDir.toString());
  }

  @Test
  void addComment_success_withMentions() throws IOException {
    // Create physical file
    Path targetFile = Files.createFile(tempDir.resolve("document.pdf"));

    CreateCommentRequest request = new CreateCommentRequest();
    request.setOwnerUsername("alice");
    request.setFilePath("document.pdf");
    request.setContent("Hey @bob check out this file!");

    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
    doNothing().when(folderService).validatePath(eq(alice.getRootFolderPath()), any(Path.class));

    Comment savedComment = new Comment();
    savedComment.setId(42L);
    savedComment.setOwnerUsername("alice");
    savedComment.setFilePath("document.pdf");
    savedComment.setAuthorUsername("alice");
    savedComment.setContent(request.getContent());
    savedComment.setCreatedAt(Instant.now());

    when(commentRepository.save(any(Comment.class))).thenReturn(savedComment);

    CommentDto result = service.addComment(alice, request);

    assertNotNull(result);
    assertEquals(42L, result.getId());
    assertEquals("alice", result.getAuthorUsername());
    assertEquals("document.pdf", result.getFilePath());
    assertEquals("Hey @bob check out this file!", result.getContent());

    verify(notificationService)
        .sendNotification(
            eq("bob"),
            eq("MENTION"),
            eq("alice mentioned you in a comment on document.pdf"),
            eq("document.pdf"),
            eq("alice"));
  }

  @Test
  void addComment_selfMention_ignored() throws IOException {
    Path targetFile = Files.createFile(tempDir.resolve("document.pdf"));

    CreateCommentRequest request = new CreateCommentRequest();
    request.setOwnerUsername("alice");
    request.setFilePath("document.pdf");
    request.setContent("Hey @alice self mention check");

    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    doNothing().when(folderService).validatePath(eq(alice.getRootFolderPath()), any(Path.class));

    Comment savedComment = new Comment();
    savedComment.setId(43L);
    savedComment.setOwnerUsername("alice");
    savedComment.setFilePath("document.pdf");
    savedComment.setAuthorUsername("alice");
    savedComment.setContent(request.getContent());
    savedComment.setCreatedAt(Instant.now());

    when(commentRepository.save(any(Comment.class))).thenReturn(savedComment);

    service.addComment(alice, request);

    verify(notificationService, never()).sendNotification(any(), any(), any(), any(), any());
  }

  @Test
  void addComment_targetFileNotFound_throwsException() {
    CreateCommentRequest request = new CreateCommentRequest();
    request.setOwnerUsername("alice");
    request.setFilePath("missing.pdf");
    request.setContent("Hello missing file");

    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

    assertThrows(FileNotFoundException.class, () -> service.addComment(alice, request));
  }

  @Test
  void addComment_unauthorizedUser_throwsException() {
    CreateCommentRequest request = new CreateCommentRequest();
    request.setOwnerUsername("alice");
    request.setFilePath("document.pdf");
    request.setContent("Hi");

    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

    assertThrows(UnauthorizedAccessException.class, () -> service.addComment(bob, request));
  }

  @Test
  void listComments_success() throws IOException {
    Path targetFile = Files.createFile(tempDir.resolve("document.pdf"));

    when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
    doNothing().when(folderService).validatePath(eq(alice.getRootFolderPath()), any(Path.class));

    Comment comment = new Comment();
    comment.setId(101L);
    comment.setOwnerUsername("alice");
    comment.setFilePath("document.pdf");
    comment.setAuthorUsername("alice");
    comment.setContent("Initial note");
    comment.setCreatedAt(Instant.now());

    when(commentRepository.findByOwnerUsernameAndFilePathOrderByCreatedAtAsc(
            "alice", "document.pdf"))
        .thenReturn(Collections.singletonList(comment));

    List<CommentDto> result = service.listComments(alice, "alice", "document.pdf");

    assertEquals(1, result.size());
    assertEquals(101L, result.get(0).getId());
    assertEquals("Initial note", result.get(0).getContent());
  }

  @Test
  void deleteComment_byAuthor_success() {
    Comment comment = new Comment();
    comment.setId(200L);
    comment.setOwnerUsername("alice");
    comment.setFilePath("document.pdf");
    comment.setAuthorUsername("bob");

    when(commentRepository.findById(200L)).thenReturn(Optional.of(comment));

    service.deleteComment(bob, 200L);

    verify(commentRepository).delete(comment);
  }

  @Test
  void deleteComment_byOwner_success() {
    Comment comment = new Comment();
    comment.setId(200L);
    comment.setOwnerUsername("alice");
    comment.setFilePath("document.pdf");
    comment.setAuthorUsername("bob");

    when(commentRepository.findById(200L)).thenReturn(Optional.of(comment));

    service.deleteComment(alice, 200L);

    verify(commentRepository).delete(comment);
  }

  @Test
  void deleteComment_byUnauthorizedUser_throwsException() {
    Comment comment = new Comment();
    comment.setId(200L);
    comment.setOwnerUsername("alice");
    comment.setFilePath("document.pdf");
    comment.setAuthorUsername("alice");

    when(commentRepository.findById(200L)).thenReturn(Optional.of(comment));

    assertThrows(UnauthorizedAccessException.class, () -> service.deleteComment(bob, 200L));
  }
}
