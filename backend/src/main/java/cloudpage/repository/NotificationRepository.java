package cloudpage.repository;

import cloudpage.model.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository interface for user notifications. */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
  List<Notification> findByRecipientUsernameOrderByCreatedAtDesc(String recipientUsername);

  List<Notification> findByRecipientUsernameAndReadOrderByCreatedAtDesc(
      String recipientUsername, boolean read);

  Optional<Notification> findByIdAndRecipientUsername(Long id, String recipientUsername);
}
