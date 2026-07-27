package cloudpage.repository;

import cloudpage.model.ResourceShare;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResourceShareRepository extends JpaRepository<ResourceShare, String> {

  List<ResourceShare> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

  List<ResourceShare> findByRecipientIdAndRevokedAtIsNullOrderByCreatedAtDesc(String recipientId);

  Optional<ResourceShare> findByIdAndOwnerId(String id, String ownerId);

  Optional<ResourceShare> findByIdAndRecipientIdAndRevokedAtIsNull(String id, String recipientId);

  Optional<ResourceShare> findByOwnerIdAndRecipientIdAndRelativePathAndRevokedAtIsNull(
      String ownerId, String recipientId, String relativePath);
}
