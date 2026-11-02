package cloudpage.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * A file that has been soft-deleted into a user's trash. The {@link #id} also serves as the file
 * name under the user's {@code .trash} directory. An entry is removed once its file is restored,
 * permanently deleted, or purged after the retention period.
 */
@Entity
@Table(name = "trash_entries")
@Getter
@Setter
public class TrashEntry {

  @Id private String id;

  @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
  public Set<TrashEntry> children;

  @ManyToOne
  @JoinColumn(name = "parent_id")
  TrashEntry parent;

  private String userId;

  /** Path, relative to the user's root, the file was deleted from and should be restored to. */
  private String originalPath;

  /** Original file or folder name, shown in the trash listing. */
  private String displayName;

  private Instant deletedAt;

  private long sizeBytes;

  public void addChild(TrashEntry child) {
    children.add(child);
    child.setParent(this);
  }
}
