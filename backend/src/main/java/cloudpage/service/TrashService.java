package cloudpage.service;

import cloudpage.dto.TrashEntryDto;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.model.TrashEntry;
import cloudpage.model.User;
import cloudpage.repository.TrashEntryRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Implements a per-user trash (soft delete). Deleting a file moves it into a hidden {@code .trash}
 * directory under the user's root and records it in the database, from where it can be listed,
 * restored, or permanently removed. Entries whose retention period has elapsed are purged
 * automatically on a schedule.
 *
 * <p>The trash directory lives inside the user's root so it stays within the existing path security
 * boundary; it is excluded from normal folder listings.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrashService {

  /** Name of the per-user trash directory, relative to the user's root. */
  public static final String TRASH_DIR = ".trash";

  private final TrashEntryRepository trashEntryRepository;
  private final UserRepository userRepository;
  private final FolderService folderService;

  @Value("${cloudpage.trash.retention-days:30}")
  private int retentionDays;

  /**
   * Moves a file into the user's trash instead of deleting it permanently.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param userId the id of the owning user
   * @param relativeFilePath the path, relative to the root, of the file to trash
   * @throws IOException if the file cannot be moved
   * @throws ResourceNotFoundException if the file does not exist or is not a regular file
   */
  public void moveToTrash(String rootPath, String userId, String relativeFilePath)
      throws IOException {
    Path source = Paths.get(rootPath, relativeFilePath).normalize();
    folderService.validatePath(rootPath, source);
    if (!Files.isRegularFile(source)) {
      throw new ResourceNotFoundException("File", "FilePath", relativeFilePath);
    }

    Path trashDir = Paths.get(rootPath, TRASH_DIR);
    Files.createDirectories(trashDir);

    String id = UUID.randomUUID().toString();
    long size = Files.size(source);
    Path target = trashDir.resolve(id);
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

    TrashEntry entry = new TrashEntry();
    entry.setId(id);
    entry.setUserId(userId);
    entry.setOriginalPath(relativeFilePath);
    entry.setDisplayName(source.getFileName().toString());
    entry.setDeletedAt(Instant.now());
    entry.setSizeBytes(size);
    try {
      trashEntryRepository.save(entry);
    } catch (RuntimeException e) {
      // Compensate: move the file back so a failed DB write does not leave an orphan in the trash.
      Files.move(target, source, StandardCopyOption.REPLACE_EXISTING);
      throw e;
    }
  }

  /**
   * Lists the current contents of a user's trash.
   *
   * @param userId the id of the owning user
   * @return the trashed entries belonging to the user
   */
  public List<TrashEntryDto> listTrash(String userId) {
    return trashEntryRepository.findByUserId(userId).stream()
        .map(
            entry ->
                new TrashEntryDto(
                    entry.getId(),
                    entry.getDisplayName(),
                    entry.getOriginalPath(),
                    entry.getDeletedAt(),
                    entry.getSizeBytes()))
        .toList();
  }

  /**
   * Restores a trashed file back to its original location, recreating parent folders if needed.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param userId the id of the owning user
   * @param entryId the id of the trash entry to restore
   * @throws IOException if the file cannot be moved back
   * @throws ResourceNotFoundException if no matching trash entry exists for the user
   */
  public void restore(String rootPath, String userId, String entryId) throws IOException {
    TrashEntry entry =
        trashEntryRepository
            .findByIdAndUserId(entryId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("TrashEntry", "id", entryId));

    Path trashed = Paths.get(rootPath, TRASH_DIR, entry.getId()).normalize();
    folderService.validatePath(rootPath, trashed);

    Path target = Paths.get(rootPath, entry.getOriginalPath()).normalize();
    folderService.validatePath(rootPath, target.getParent());
    if (Files.exists(target)) {
      throw new FileAlreadyExistsException(
          "Cannot restore: a file already exists at " + entry.getOriginalPath());
    }
    Files.createDirectories(target.getParent());
    Files.move(trashed, target);

    trashEntryRepository.delete(entry);
  }

  /**
   * Permanently removes a single file from the user's trash.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param userId the id of the owning user
   * @param entryId the id of the trash entry to remove
   * @throws IOException if the file cannot be deleted
   * @throws ResourceNotFoundException if no matching trash entry exists for the user
   */
  public void purge(String rootPath, String userId, String entryId) throws IOException {
    TrashEntry entry =
        trashEntryRepository
            .findByIdAndUserId(entryId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("TrashEntry", "id", entryId));
    deleteTrashFile(rootPath, entry);
    trashEntryRepository.delete(entry);
  }

  /**
   * Permanently removes every trashed file whose retention period has elapsed. Runs on a schedule
   * (daily by default).
   */
  @Scheduled(cron = "${cloudpage.trash.cleanup-cron:0 0 3 * * *}")
  public void purgeExpired() {
    Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
    for (TrashEntry entry : trashEntryRepository.findByDeletedAtBefore(cutoff)) {
      User user = userRepository.findById(entry.getUserId()).orElse(null);
      if (user == null) {
        // The owning user no longer exists; drop the orphaned entry.
        trashEntryRepository.delete(entry);
        continue;
      }
      try {
        deleteTrashFile(user.getRootFolderPath(), entry);
        trashEntryRepository.delete(entry);
      } catch (IOException e) {
        // Best effort: keep the entry so it is retried on the next run.
        log.warn("Failed to purge expired trash entry {}: {}", entry.getId(), e.getMessage());
      }
    }
  }

  private void deleteTrashFile(String rootPath, TrashEntry entry) throws IOException {
    Path file = Paths.get(rootPath, TRASH_DIR, entry.getId()).normalize();
    folderService.validatePath(rootPath, file);
    Files.deleteIfExists(file);
  }
}
