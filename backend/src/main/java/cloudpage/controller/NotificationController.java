package cloudpage.controller;

import cloudpage.dto.NotificationDto;
import cloudpage.service.NotificationService;
import cloudpage.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller for retrieving and managing notifications. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notificationService;
  private final UserService userService;

  /** Lists notifications for the current authenticated user. */
  @GetMapping
  public ResponseEntity<List<NotificationDto>> listNotifications(
      @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
    var user = userService.getCurrentUser();
    List<NotificationDto> notifications =
        notificationService.getNotificationsForUser(user.getUsername(), unreadOnly);
    return ResponseEntity.ok(notifications);
  }

  /** Marks a notification as read. */
  @PostMapping("/{id}/read")
  public ResponseEntity<Void> markAsRead(@PathVariable Long id) {
    var user = userService.getCurrentUser();
    notificationService.markAsRead(user.getUsername(), id);
    return ResponseEntity.ok().build();
  }

  /** Marks all notifications for the current user as read. */
  @PostMapping("/read-all")
  public ResponseEntity<Void> markAllAsRead() {
    var user = userService.getCurrentUser();
    notificationService.markAllAsRead(user.getUsername());
    return ResponseEntity.ok().build();
  }
}
