package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.FolderDto;
import cloudpage.dto.PageResponseDto;
import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderServiceTest {

  private FolderService folderService;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    folderService = new FolderService();
  }

  // ── getFolderTree(rootPath) ──────────────────────────────────────────────

  @Test
  void getFolderTree_emptyFolder_returnsEmptyLists() throws IOException {
    FolderDto tree = folderService.getFolderTree(tempDir.toString());

    assertNotNull(tree);
    assertTrue(tree.getFolders().isEmpty());
    assertTrue(tree.getFiles().isEmpty());
  }

  @Test
  void getFolderTree_withFiles_returnsFileList() throws IOException {
    Files.writeString(tempDir.resolve("file1.txt"), "content1");
    Files.writeString(tempDir.resolve("file2.txt"), "content2");

    FolderDto tree = folderService.getFolderTree(tempDir.toString());

    assertEquals(2, tree.getFiles().size());
    assertTrue(tree.getFolders().isEmpty());
  }

  @Test
  void getFolderTree_nestedFoldersWithFiles_returnsFullTree() throws IOException {
    Path sub = Files.createDirectory(tempDir.resolve("sub"));
    Files.writeString(tempDir.resolve("root.txt"), "root");
    Files.writeString(sub.resolve("child.txt"), "child");

    FolderDto tree = folderService.getFolderTree(tempDir.toString());

    assertEquals(1, tree.getFiles().size());
    assertEquals(1, tree.getFolders().size());
    assertEquals("sub", tree.getFolders().get(0).getName());
    assertEquals(1, tree.getFolders().get(0).getFiles().size());
    assertEquals("child.txt", tree.getFolders().get(0).getFiles().get(0).getName());
  }

  @Test
  void getFolderTree_nonExistentRoot_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.getFolderTree(tempDir.resolve("nope").toString()));
  }

  // ── getFolderTree(rootPath, relativePath) ────────────────────────────────

  @Test
  void getFolderTreeWithRelativePath_validSubfolder_returnsSubtree() throws IOException {
    Path sub = Files.createDirectory(tempDir.resolve("docs"));
    Files.writeString(sub.resolve("readme.md"), "# Readme");

    FolderDto tree = folderService.getFolderTree(tempDir.toString(), "docs");

    assertEquals("docs", tree.getName());
    assertEquals(1, tree.getFiles().size());
    assertEquals("readme.md", tree.getFiles().get(0).getName());
  }

  @Test
  void getFolderTreeWithRelativePath_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.getFolderTree(tempDir.toString(), "../../etc"));
  }

  // ── createFolder ─────────────────────────────────────────────────────────

  @Test
  void createFolder_normalCreate_createsFolderAndReturnsPath() throws IOException {
    Path created = folderService.createFolder(tempDir.toString(), "", "newFolder");

    assertTrue(Files.isDirectory(created));
    assertEquals("newFolder", created.getFileName().toString());
  }

  @Test
  void createFolder_nestedUnderExistingFolder() throws IOException {
    Files.createDirectory(tempDir.resolve("parent"));

    Path created = folderService.createFolder(tempDir.toString(), "parent", "child");

    assertTrue(Files.isDirectory(created));
    assertTrue(created.endsWith("child"));
  }

  @Test
  void createFolder_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.createFolder(tempDir.toString(), "../../", "hack"));
  }

  // ── deleteFolder ─────────────────────────────────────────────────────────

  @Test
  void deleteFolder_emptyFolder_deletesSuccessfully() throws IOException {
    Files.createDirectory(tempDir.resolve("empty"));

    folderService.deleteFolder(tempDir.toString(), "empty");

    assertFalse(Files.exists(tempDir.resolve("empty")));
  }

  @Test
  void deleteFolder_folderWithContents_deletesRecursively() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("full"));
    Path sub = Files.createDirectory(dir.resolve("sub"));
    Files.writeString(dir.resolve("file.txt"), "data");
    Files.writeString(sub.resolve("nested.txt"), "nested");

    folderService.deleteFolder(tempDir.toString(), "full");

    assertFalse(Files.exists(tempDir.resolve("full")));
  }

  @Test
  void deleteFolder_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.deleteFolder(tempDir.toString(), "../../etc"));
  }

  // ── renameOrMoveFolder ───────────────────────────────────────────────────

  @Test
  void renameOrMoveFolder_rename_successfullyRenames() throws IOException {
    Files.createDirectory(tempDir.resolve("oldName"));

    folderService.renameOrMoveFolder(tempDir.toString(), "oldName", "newName");

    assertFalse(Files.exists(tempDir.resolve("oldName")));
    assertTrue(Files.isDirectory(tempDir.resolve("newName")));
  }

  @Test
  void renameOrMoveFolder_moveToSubfolder() throws IOException {
    Files.createDirectory(tempDir.resolve("source"));
    Files.createDirectory(tempDir.resolve("target"));

    folderService.renameOrMoveFolder(tempDir.toString(), "source", "target/source");

    assertFalse(Files.exists(tempDir.resolve("source")));
    assertTrue(Files.isDirectory(tempDir.resolve("target/source")));
  }

  @Test
  void renameOrMoveFolder_pathTraversal_throwsInvalidPathException() throws IOException {
    Files.createDirectory(tempDir.resolve("safe"));

    assertThrows(
        InvalidPathException.class,
        () -> folderService.renameOrMoveFolder(tempDir.toString(), "safe", "../../evil"));
  }

  // ── validatePath ─────────────────────────────────────────────────────────

  @Test
  void validatePath_validPath_doesNotThrow() {
    Path valid = tempDir.resolve("somefile.txt");

    assertDoesNotThrow(() -> folderService.validatePath(tempDir.toString(), valid));
  }

  @Test
  void validatePath_outsideRoot_throwsInvalidPathException() {
    Path outside = tempDir.resolve("../../etc/passwd").normalize();

    assertThrows(
        InvalidPathException.class, () -> folderService.validatePath(tempDir.toString(), outside));
  }

  // ── FileDto content verification ─────────────────────────────────────────

  @Test
  void getFolderTree_fileDtoContainsCorrectMetadata() throws IOException {
    Files.writeString(tempDir.resolve("info.txt"), "12345");

    FolderDto tree = folderService.getFolderTree(tempDir.toString());

    assertEquals(1, tree.getFiles().size());
    assertEquals("info.txt", tree.getFiles().get(0).getName());
    assertEquals(5, tree.getFiles().get(0).getSize());
    assertNotNull(tree.getFiles().get(0).getPath());
  }

  // ── getFolderContentPage ───────────────────────────────────────────────────

  @Test
  void getFolderContentPage_emptyFolder_returnsEmptyPage() throws IOException {
    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, null);

    assertNotNull(result);
    assertEquals(0, result.getTotalElements());
    assertEquals(0, result.getTotalPages());
    assertEquals(0, result.getPageNumber());
    assertTrue(result.getContent().isEmpty());
  }

  @Test
  void getFolderContentPage_firstPage_returnsCorrectItems() throws IOException {
    Files.writeString(tempDir.resolve("file1.txt"), "content1");
    Files.writeString(tempDir.resolve("file2.txt"), "content2");
    Files.writeString(tempDir.resolve("file3.txt"), "content3");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 2, null);

    assertNotNull(result);
    assertEquals(3, result.getTotalElements());
    assertEquals(2, result.getTotalPages());
    assertEquals(0, result.getPageNumber());
    assertEquals(2, result.getContent().size());
  }

  @Test
  void getFolderContentPage_secondPage_returnsCorrectItems() throws IOException {
    Files.writeString(tempDir.resolve("file1.txt"), "content1");
    Files.writeString(tempDir.resolve("file2.txt"), "content2");
    Files.writeString(tempDir.resolve("file3.txt"), "content3");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 1, 2, null);

    assertNotNull(result);
    assertEquals(3, result.getTotalElements());
    assertEquals(2, result.getTotalPages());
    assertEquals(1, result.getPageNumber());
    assertEquals(1, result.getContent().size());
  }

  @Test
  void getFolderContentPage_pageBeyondData_returnsEmptyContent() throws IOException {
    Files.writeString(tempDir.resolve("file1.txt"), "content1");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 5, 10, null);

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
    assertEquals(1, result.getTotalPages());
    assertEquals(5, result.getPageNumber());
    assertTrue(result.getContent().isEmpty());
  }

  @Test
  void getFolderContentPage_sizeLargerThanTotal_returnsAllItems() throws IOException {
    Files.writeString(tempDir.resolve("file1.txt"), "content1");
    Files.writeString(tempDir.resolve("file2.txt"), "content2");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 100, null);

    assertNotNull(result);
    assertEquals(2, result.getTotalElements());
    assertEquals(1, result.getTotalPages());
    assertEquals(0, result.getPageNumber());
    assertEquals(2, result.getContent().size());
  }

  @Test
  void getFolderContentPage_withFoldersAndFiles_returnsBoth() throws IOException {
    Files.createDirectory(tempDir.resolve("folder1"));
    Files.createDirectory(tempDir.resolve("folder2"));
    Files.writeString(tempDir.resolve("file1.txt"), "content1");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, null);

    assertNotNull(result);
    assertEquals(3, result.getTotalElements());
    assertTrue(result.getContent().stream().anyMatch(FolderContentItemDto::isDirectory));
    assertTrue(result.getContent().stream().anyMatch(item -> !item.isDirectory()));
  }

  @Test
  void getFolderContentPage_sortByNameAscending_returnsSortedItems() throws IOException {
    Files.writeString(tempDir.resolve("zebra.txt"), "z");
    Files.writeString(tempDir.resolve("apple.txt"), "a");
    Files.writeString(tempDir.resolve("banana.txt"), "b");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, "name,asc");

    assertNotNull(result);
    assertEquals(3, result.getContent().size());
    assertEquals("apple.txt", result.getContent().get(0).getName());
    assertEquals("banana.txt", result.getContent().get(1).getName());
    assertEquals("zebra.txt", result.getContent().get(2).getName());
  }

  @Test
  void getFolderContentPage_sortByNameDescending_returnsSortedItems() throws IOException {
    Files.writeString(tempDir.resolve("apple.txt"), "a");
    Files.writeString(tempDir.resolve("banana.txt"), "b");
    Files.writeString(tempDir.resolve("zebra.txt"), "z");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, "name,desc");

    assertNotNull(result);
    assertEquals(3, result.getContent().size());
    assertEquals("zebra.txt", result.getContent().get(0).getName());
    assertEquals("banana.txt", result.getContent().get(1).getName());
    assertEquals("apple.txt", result.getContent().get(2).getName());
  }

  @Test
  void getFolderContentPage_sortDefault_returnsSortedByNameAscending() throws IOException {
    Files.writeString(tempDir.resolve("zebra.txt"), "z");
    Files.writeString(tempDir.resolve("apple.txt"), "a");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, null);

    assertNotNull(result);
    assertEquals(2, result.getContent().size());
    assertEquals("apple.txt", result.getContent().get(0).getName());
    assertEquals("zebra.txt", result.getContent().get(1).getName());
  }

  @Test
  void getFolderContentPage_sortEmptyString_usesDefaultSort() throws IOException {
    Files.writeString(tempDir.resolve("zebra.txt"), "z");
    Files.writeString(tempDir.resolve("apple.txt"), "a");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, "");

    assertNotNull(result);
    assertEquals(2, result.getContent().size());
    assertEquals("apple.txt", result.getContent().get(0).getName());
  }

  @Test
  void getFolderContentPage_withSubfolder_returnsCorrectItems() throws IOException {
    Path subFolder = Files.createDirectory(tempDir.resolve("sub"));
    Files.writeString(tempDir.resolve("root.txt"), "root");
    Files.writeString(subFolder.resolve("sub.txt"), "sub");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "sub", 0, 10, null);

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
    assertEquals("sub.txt", result.getContent().get(0).getName());
    assertFalse(result.getContent().get(0).isDirectory());
  }

  @Test
  void getFolderContentPage_negativePage_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> folderService.getFolderContentPage(tempDir.toString(), "", -1, 10, null));
  }

  @Test
  void getFolderContentPage_zeroSize_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> folderService.getFolderContentPage(tempDir.toString(), "", 0, 0, null));
  }

  @Test
  void getFolderContentPage_negativeSize_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> folderService.getFolderContentPage(tempDir.toString(), "", 0, -5, null));
  }

  @Test
  void getFolderContentPage_nonExistentFolder_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.getFolderContentPage(tempDir.toString(), "nonexistent", 0, 10, null));
  }

  @Test
  void getFolderContentPage_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> folderService.getFolderContentPage(tempDir.toString(), "../../etc", 0, 10, null));
  }

  @Test
  void getFolderContentPage_fileMetadata_containsCorrectFields() throws IOException {
    Files.writeString(tempDir.resolve("test.txt"), "12345");

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, null);

    assertNotNull(result);
    assertEquals(1, result.getContent().size());
    FolderContentItemDto item = result.getContent().get(0);
    assertEquals("test.txt", item.getName());
    assertEquals(5, item.getSize());
    assertFalse(item.isDirectory());
    assertNotNull(item.getPath());
  }

  @Test
  void getFolderContentPage_folderMetadata_containsCorrectFields() throws IOException {
    Files.createDirectory(tempDir.resolve("testFolder"));

    PageResponseDto<FolderContentItemDto> result =
        folderService.getFolderContentPage(tempDir.toString(), "", 0, 10, null);

    assertNotNull(result);
    assertEquals(1, result.getContent().size());
    FolderContentItemDto item = result.getContent().get(0);
    assertEquals("testFolder", item.getName());
    assertEquals(0, item.getSize());
    assertTrue(item.isDirectory());
    assertNotNull(item.getPath());
  }
}
