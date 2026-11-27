package cloudpage.controller;

import cloudpage.dto.CommentDto;
import cloudpage.dto.CreateCommentRequest;
import cloudpage.service.CommentService;
import cloudpage.service.UserService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller for CRUD actions on comments left on files/folders. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/comments")
public class CommentController {

  private final CommentService commentService;
  private final UserService userService;

  /** Adds a new comment on a file or folder. */
  @PostMapping
  public ResponseEntity<CommentDto> addComment(@Valid @RequestBody CreateCommentRequest request)
      throws IOException {
    var user = userService.getCurrentUser();
    CommentDto comment = commentService.addComment(user, request);
    return ResponseEntity.ok(comment);
  }

  /** Lists all comments on a specific file or folder. */
  @GetMapping
  public ResponseEntity<List<CommentDto>> listComments(
      @RequestParam String ownerUsername, @RequestParam String filePath) throws IOException {
    var user = userService.getCurrentUser();
    List<CommentDto> comments = commentService.listComments(user, ownerUsername, filePath);
    return ResponseEntity.ok(comments);
  }

  /** Deletes a comment by ID. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteComment(@PathVariable Long id) {
    var user = userService.getCurrentUser();
    commentService.deleteComment(user, id);
    return ResponseEntity.ok().build();
  }
}
