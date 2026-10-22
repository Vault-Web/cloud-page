package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import cloudpage.dto.FolderDto;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.util.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderListingSafetyTest {
  @TempDir Path tempDir;
  private Path root;
  private Path outside;
  private final FolderService service = new FolderService();

  @BeforeEach
  void setup() throws IOException {
    root = Files.createDirectory(tempDir.resolve("root"));
    outside = Files.createDirectory(tempDir.resolve("outside"));
  }

  @Test
  void shallowListingSkipsExternalFileAndDirectoryLinks() throws IOException {
    Files.writeString(root.resolve("safe.txt"), "safe");
    link(root.resolve("secret.txt"), Files.writeString(outside.resolve("secret.txt"), "secret"));
    link(root.resolve("external-dir"), outside);

    FolderDto result = service.getFolderTree(root.toString());

    assertEquals(1, result.getFiles().size());
    assertEquals("safe.txt", result.getFiles().getFirst().getName());
    assertTrue(result.getFolders().isEmpty());
  }

  @Test
  void paginatedListingExcludesUnsafeEntriesBeforeCountingAndPaging() throws IOException {
    Files.writeString(root.resolve("a.txt"), "a");
    Files.writeString(root.resolve("b.txt"), "b");
    link(root.resolve("0-secret.txt"), Files.writeString(outside.resolve("secret.txt"), "secret"));
    link(root.resolve("0-external-dir"), outside);

    var first = service.getFolderContentPage(root.toString(), "", 0, 1, "name,asc");
    var second = service.getFolderContentPage(root.toString(), "", 1, 1, "name,asc");

    assertEquals(2, first.getTotalElements());
    assertEquals(2, first.getTotalPages());
    assertEquals("a.txt", first.getContent().getFirst().getName());
    assertEquals("b.txt", second.getContent().getFirst().getName());
  }

  @Test
  void childCountsIgnoreExternalAndDanglingLinks() throws IOException {
    Path sub = Files.createDirectory(root.resolve("sub"));
    Files.writeString(sub.resolve("safe.txt"), "safe");
    link(sub.resolve("external"), outside);
    link(sub.resolve("dangling"), outside.resolve("missing"));

    var result = service.getFolderTree(root.toString(), true);

    assertEquals(1, result.getFolders().getFirst().getDirectChildrenCount());
  }

  @Test
  void internalSymlinksRemainVisibleAndResolveInsideRoot() throws IOException {
    Path nested = Files.createDirectory(root.resolve("nested"));
    Path target = Files.writeString(nested.resolve("target.txt"), "safe");
    link(root.resolve("alias.txt"), target);

    var tree = service.getFolderTree(root.toString());
    var file = tree.getFiles().getFirst();
    assertEquals("alias.txt", file.getName());
    assertEquals("nested/target.txt", file.getPath().replace('\\', '/'));

    var page = service.getFolderContentPage(root.toString(), "", 0, 10, "name,asc");
    var alias =
        page.getContent().stream()
            .filter(item -> item.getName().equals("alias.txt"))
            .findFirst()
            .orElseThrow();
    assertEquals("nested/target.txt", alias.getPath().replace('\\', '/'));
  }

  @Test
  void danglingLinksDoNotBreakEitherListing() throws IOException {
    Files.writeString(root.resolve("safe.txt"), "safe");
    link(root.resolve("dangling.txt"), outside.resolve("missing"));

    var tree = service.getFolderTree(root.toString());
    var page = service.getFolderContentPage(root.toString(), "", 0, 10, "name,asc");

    assertEquals(1, tree.getFiles().size());
    assertEquals("safe.txt", tree.getFiles().getFirst().getName());
    assertEquals(1, page.getTotalElements());
    assertEquals("safe.txt", page.getContent().getFirst().getName());
  }

  @Test
  void explicitlyRequestedExternalFolderStillFailsValidation() throws IOException {
    link(root.resolve("external"), outside);

    assertThrows(
        InvalidPathException.class, () -> service.getFolderTree(root.toString(), "external"));
    assertThrows(
        InvalidPathException.class,
        () -> service.getFolderContentPage(root.toString(), "external", 0, 10, "name,asc"));
  }

  @Test
  void shallowListingKeepsConflictWhenOriginalFailsContainmentValidation() throws IOException {
    assertRejectedConflictOriginalDoesNotBreakListing(false);
  }

  @Test
  void paginatedListingKeepsConflictWhenOriginalFailsContainmentValidation() throws IOException {
    assertRejectedConflictOriginalDoesNotBreakListing(true);
  }

  private void assertRejectedConflictOriginalDoesNotBreakListing(boolean paginated)
      throws IOException {
    Path original = Files.writeString(root.resolve("a.md"), "original");
    String conflictName = "a.sync-conflict-20261010-142530-DEV.md";
    Files.writeString(root.resolve(conflictName), "conflict");
    Path rootReal = root.toRealPath().normalize();

    // Deterministically model containment validation rejecting the original
    // after the NOFOLLOW_LINKS type check, without racing filesystem threads.
    try (var paths = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
      paths
          .when(() -> FileUtils.validateAndResolvePathWithinRoot(rootReal, original))
          .thenThrow(new InvalidPathException("Original now resolves outside root"));

      if (paginated) {
        var page = service.getFolderContentPage(root.toString(), "", 0, 10, "name,asc");
        assertEquals(1, page.getTotalElements());
        var conflict = page.getContent().getFirst();
        assertEquals(conflictName, conflict.getName());
        assertTrue(conflict.isConflict());
        assertNull(conflict.getOriginalPath());
      } else {
        var tree = service.getFolderTree(root.toString());
        assertEquals(1, tree.getFiles().size());
        var conflict = tree.getFiles().getFirst();
        assertEquals(conflictName, conflict.getName());
        assertTrue(conflict.isConflict());
        assertNull(conflict.getOriginalPath());
      }
    }
  }

  private static void link(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException exception) {
      assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
    }
  }
}
