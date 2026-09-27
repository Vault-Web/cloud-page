package cloudpage.repository;

import cloudpage.model.Comment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository interface for comments. */
@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {
  List<Comment> findByOwnerUsernameAndFilePathOrderByCreatedAtAsc(
      String ownerUsername, String filePath);
}
