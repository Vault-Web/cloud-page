package cloudpage.repository;

import cloudpage.model.SecureSend;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecureSendRepository extends JpaRepository<SecureSend, String> {

  List<SecureSend> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

  Optional<SecureSend> findByIdAndOwnerId(String id, String ownerId);

  Optional<SecureSend> findByTokenHash(String tokenHash);

  void deleteByExpiresAtBefore(Instant cutoff);
}
