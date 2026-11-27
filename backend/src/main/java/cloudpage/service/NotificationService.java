package cloudpage.service;

import cloudpage.dto.NotificationDto;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.model.Notification;
import cloudpage.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service handling notification operations like sending notifications, marking them as read. */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;

  /** Sends/creates a notification for a recipient user. */
  @Transactional
  public void sendNotification(
      String recipientUsername,
      String type,
      String message,
      String filePath,
      String ownerUsername) {
    Notification notification = new Notification();
    notification.setRecipientUsername(recipientUsername);
    notification.setType(type);
    notification.setMessage(message);
    notification.setFilePath(filePath);
    notification.setOwnerUsername(ownerUsername);
    notification.setRead(false);
    notification.setCreatedAt(Instant.now());
    notification.setId(null);

    notificationRepository.save(notification);
  }

  /** Lists notifications for the given user, optionally filtering by unread state. */
  @Transactional(readOnly = true)
  public List<NotificationDto> getNotificationsForUser(String username, Boolean unreadOnly) {
    List<Notification> notifications;
    if (Boolean.TRUE.equals(unreadOnly)) {
      notifications =
          notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc(
              username, false);
    } else {
      notifications = notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc(username);
    }

    return notifications.stream().map(this::mapToDto).collect(Collectors.toList());
  }

  /** Marks a specific notification as read. */
  @Transactional
  public void markAsRead(String username, Long notificationId) {
    Notification notification =
        notificationRepository
            .findByIdAndRecipientUsername(notificationId, username)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("Notification", "Id", notificationId.toString()));
    notification.setRead(true);
    notificationRepository.save(notification);
  }

  /** Marks all notifications for a user as read. */
  @Transactional
  public void markAllAsRead(String username) {
    List<Notification> unread =
        notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc(username, false);
    for (Notification n : unread) {
      n.setRead(true);
    }
    notificationRepository.saveAll(unread);
  }

  private NotificationDto mapToDto(Notification entity) {
    return new NotificationDto(
        entity.getId(),
        entity.getRecipientUsername(),
        entity.getType(),
        entity.getMessage(),
        entity.getFilePath(),
        entity.getOwnerUsername(),
        entity.isRead(),
        entity.getCreatedAt());
  }
}
