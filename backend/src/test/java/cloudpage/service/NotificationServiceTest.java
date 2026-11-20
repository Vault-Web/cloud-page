package cloudpage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloudpage.dto.NotificationDto;
import cloudpage.model.Notification;
import cloudpage.repository.NotificationRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock private NotificationRepository notificationRepository;

  private NotificationService service;

  @BeforeEach
  void setUp() {
    service = new NotificationService(notificationRepository);
  }

  @Test
  void sendNotification_success() {
    service.sendNotification(
        "recipient", "MENTION", "Alice mentioned you", "document.pdf", "alice");

    verify(notificationRepository).save(any(Notification.class));
  }

  @Test
  void getNotificationsForUser_all() {
    Notification n = new Notification();
    n.setId(1L);
    n.setRecipientUsername("recipient");
    n.setType("MENTION");
    n.setMessage("message");
    n.setFilePath("doc.pdf");
    n.setOwnerUsername("owner");
    n.setRead(false);
    n.setCreatedAt(Instant.now());

    when(notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc("recipient"))
        .thenReturn(Collections.singletonList(n));

    List<NotificationDto> result = service.getNotificationsForUser("recipient", false);

    assertEquals(1, result.size());
    assertEquals("message", result.get(0).getMessage());
    assertFalse(result.get(0).isRead());
  }

  @Test
  void getNotificationsForUser_unreadOnly() {
    Notification n = new Notification();
    n.setId(2L);
    n.setRecipientUsername("recipient");
    n.setType("MENTION");
    n.setMessage("message2");
    n.setFilePath("doc.pdf");
    n.setOwnerUsername("owner");
    n.setRead(false);
    n.setCreatedAt(Instant.now());

    when(notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc(
            "recipient", false))
        .thenReturn(Collections.singletonList(n));

    List<NotificationDto> result = service.getNotificationsForUser("recipient", true);

    assertEquals(1, result.size());
    assertEquals("message2", result.get(0).getMessage());
  }

  @Test
  void markAsRead_success() {
    Notification n = new Notification();
    n.setId(10L);
    n.setRecipientUsername("recipient");
    n.setRead(false);

    when(notificationRepository.findByIdAndRecipientUsername(10L, "recipient"))
        .thenReturn(Optional.of(n));

    service.markAsRead("recipient", 10L);

    assertTrue(n.isRead());
    verify(notificationRepository).save(n);
  }

  @Test
  void markAllAsRead_success() {
    Notification n1 = new Notification();
    n1.setId(11L);
    n1.setRecipientUsername("recipient");
    n1.setRead(false);

    Notification n2 = new Notification();
    n2.setId(12L);
    n2.setRecipientUsername("recipient");
    n2.setRead(false);

    when(notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc(
            "recipient", false))
        .thenReturn(Arrays.asList(n1, n2));

    service.markAllAsRead("recipient");

    assertTrue(n1.isRead());
    assertTrue(n2.isRead());
    verify(notificationRepository).saveAll(any());
  }
}
