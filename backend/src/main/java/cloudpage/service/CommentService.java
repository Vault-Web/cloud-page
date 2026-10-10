package cloudpage.service;

import cloudpage.dto.CommentDto;
import cloudpage.dto.CreateCommentRequest;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.UnauthorizedAccessException;
import cloudpage.model.Comment;
import cloudpage.model.User;
import cloudpage.repository.CommentRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service handling comments creation, listing, deletion, and sending @mention notifications. */
@Service
@RequiredArgsConstructor
public class CommentService {

  private final CommentRepository commentRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final FolderService folderService;

  private static final Pattern MENTION_PATTERN = Pattern.compile("@([a-zA-Z0-9_\\-\\.]+)");

  /** Adds a new comment on a file or folder and triggers notifications for valid @mentions. */
  @Transactional
  public CommentDto addComment(User author, CreateCommentRequest request) throws IOException {
    User owner =
        userRepository
            .findByUsername(request.getOwnerUsername())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("User", "Username", request.getOwnerUsername()));

    // Check if the author has access to the owner's workspace/item
    validateAccess(author, owner.getUsername());

    // Validate that the file/folder actually exists in the workspace
    Path fullPath = Paths.get(owner.getRootFolderPath(), request.getFilePath()).normalize();
    folderService.validatePath(owner.getRootFolderPath(), fullPath);

    if (!Files.exists(fullPath)) {
      throw new FileNotFoundException(
          "Target file or folder not found at path: " + request.getFilePath());
    }

    Comment comment = new Comment();
    comment.setOwnerUsername(owner.getUsername());
    comment.setFilePath(request.getFilePath());
    comment.setAuthorUsername(author.getUsername());
    comment.setContent(request.getContent());
    comment.setCreatedAt(Instant.now());
    comment.setId(null);

    Comment saved = commentRepository.save(comment);

    // Parse and handle @mentions
    processMentions(
        author.getUsername(), request.getContent(), request.getFilePath(), owner.getUsername());

    return mapToDto(saved);
  }

  /** Lists all comments on a specific file or folder. */
  @Transactional(readOnly = true)
  public List<CommentDto> listComments(User currentUser, String ownerUsername, String filePath)
      throws IOException {
    User owner =
        userRepository
            .findByUsername(ownerUsername)
            .orElseThrow(() -> new ResourceNotFoundException("User", "Username", ownerUsername));

    // Check if the current user has access to the owner's workspace
    validateAccess(currentUser, owner.getUsername());

    // Validate that the file/folder actually exists
    Path fullPath = Paths.get(owner.getRootFolderPath(), filePath).normalize();
    folderService.validatePath(owner.getRootFolderPath(), fullPath);

    if (!Files.exists(fullPath)) {
      throw new FileNotFoundException("Target file or folder not found at path: " + filePath);
    }

    List<Comment> comments =
        commentRepository.findByOwnerUsernameAndFilePathOrderByCreatedAtAsc(
            owner.getUsername(), filePath);

    return comments.stream().map(this::mapToDto).collect(Collectors.toList());
  }

  /** Deletes a comment. Authorized for comment author or workspace owner. */
  @Transactional
  public void deleteComment(User currentUser, Long commentId) {
    Comment comment =
        commentRepository
            .findById(commentId)
            .orElseThrow(() -> new ResourceNotFoundException("Comment", "Id", commentId));

    // Access control: only the comment author or the workspace owner can delete the comment
    if (!comment.getAuthorUsername().equalsIgnoreCase(currentUser.getUsername())
        && !comment.getOwnerUsername().equalsIgnoreCase(currentUser.getUsername())) {
      throw new UnauthorizedAccessException("You are not authorized to delete this comment.");
    }

    commentRepository.delete(comment);
  }

  /** Validates access of currentUser to owner's workspace. */
  public void validateAccess(User currentUser, String ownerUsername) {
    if (currentUser.getUsername().equalsIgnoreCase(ownerUsername)) {
      return;
    }
    // Access control: only the workspace owner can access workspace elements currently.
    // In the future, if internal sharing is implemented, sharing permissions would be checked here.
    throw new UnauthorizedAccessException("You do not have access to this workspace file/folder.");
  }

  private void processMentions(
      String authorUsername, String content, String filePath, String ownerUsername) {
    if (content == null || content.isBlank()) {
      return;
    }

    Matcher matcher = MENTION_PATTERN.matcher(content);
    Set<String> mentionedUsernames = new HashSet<>();
    while (matcher.find()) {
      mentionedUsernames.add(matcher.group(1));
    }

    String itemName = getItemName(filePath);
    for (String username : mentionedUsernames) {
      // Do not notify self-mentions
      if (username.equalsIgnoreCase(authorUsername)) {
        continue;
      }

      userRepository
          .findByUsername(username)
          .ifPresent(
              recipient -> {
                String message =
                    String.format("%s mentioned you in a comment on %s", authorUsername, itemName);
                notificationService.sendNotification(
                    recipient.getUsername(), "MENTION", message, filePath, ownerUsername);
              });
    }
  }

  private String getItemName(String filePath) {
    if (filePath == null || filePath.isBlank() || ".".equals(filePath)) {
      return "root folder";
    }
    Path path = Paths.get(filePath);
    Path fileName = path.getFileName();
    return fileName != null ? fileName.toString() : filePath;
  }

  private CommentDto mapToDto(Comment comment) {
    return new CommentDto(
        comment.getId(),
        comment.getOwnerUsername(),
        comment.getFilePath(),
        comment.getAuthorUsername(),
        comment.getContent(),
        comment.getCreatedAt());
  }
}
