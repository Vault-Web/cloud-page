package cloudpage.repository;

import cloudpage.model.TrashEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrashEntryRepository extends JpaRepository<TrashEntry, String> {

  List<TrashEntry> findByUserId(String userId);

  Optional<TrashEntry> findByIdAndUserId(String id, String userId);

  List<TrashEntry> findByDeletedAtBefore(Instant cutoff);
}
