package cloudpage.controller;

import cloudpage.service.FileEntityService;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import cloudpage.dto.FileDto;
import cloudpage.model.File;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final UserService userService;
    private final FolderService folderService;
    private final FileEntityService fileEntityService;
;

    @PostMapping("/upload")
    public void uploadFile(@RequestParam String folderPath, @RequestParam MultipartFile file) throws IOException {
        var user = userService.getCurrentUser();
        fileService.uploadFile(user.getRootFolderPath(), folderPath, file);
    }

    @GetMapping("/content")
    public ResponseEntity<String> getFileContent(@RequestParam String path) throws IOException {
        var user = userService.getCurrentUser();
        Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
        folderService.validatePath(user.getRootFolderPath(), fullPath);

        if (!fullPath.toFile().exists() || !fullPath.toFile().isFile()) {
            throw new IllegalArgumentException("File does not exist: " + fullPath);
        }

        String content = fileService.readFileContent(user.getRootFolderPath(), path);
        return ResponseEntity.ok(content);
    }

    @DeleteMapping
    public void deleteFile(@RequestParam String filePath) throws IOException {
        var user = userService.getCurrentUser();
        fileService.deleteFile(user.getRootFolderPath(), filePath);
    }

    @PatchMapping("/move")
    public void renameOrMoveFile(@RequestParam String filePath, @RequestParam String newPath) throws IOException {
        var user = userService.getCurrentUser();
        fileService.renameOrMoveFile(user.getRootFolderPath(), filePath, newPath);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam String path) throws IOException {
        var user = userService.getCurrentUser();
        Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
        folderService.validatePath(user.getRootFolderPath(), fullPath); // ensure security
        Resource resource = new UrlResource(fullPath.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fullPath.getFileName() + "\"")
                .body(resource);
    }

    @GetMapping("/view")
    public ResponseEntity<Resource> viewFile(@RequestParam String path) throws IOException {
        var user = userService.getCurrentUser();
        Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
        folderService.validatePath(user.getRootFolderPath(), fullPath);

        if (!fullPath.toFile().exists() || !fullPath.toFile().isFile()) {
            throw new IllegalArgumentException("File does not exist: " + fullPath);
        }

        Resource resource = new UrlResource(fullPath.toUri());
        String mimeType = Files.probeContentType(fullPath);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .body(resource);
    }
    @GetMapping("/list")
public ResponseEntity<FileDto<File>> listFilesWithPagination(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "name,asc") String[] sort
) {
    // Example sort param: ["name", "asc"]
    Sort.Direction direction = sort[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
    Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sort[0]));
    var filePage = fileEntityService.getAllFiles(pageable);

    FileDto<File> response = new FileDto<>(
            filePage.getContent(),
            filePage.getNumber(),
            filePage.getTotalElements(),
            filePage.getTotalPages()
    );
    return ResponseEntity.ok(response);
}
}