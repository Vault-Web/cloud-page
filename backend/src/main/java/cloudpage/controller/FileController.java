package cloudpage.controller;

import cloudpage.dto.FileDto;
import cloudpage.model.File;
import cloudpage.repository.FileRepository;
import cloudpage.service.FileService;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import lombok.RequiredArgsConstructor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final UserService userService;
    private final FolderService folderService;
    private final FileRepository fileRepository;

    @PostMapping("/upload")
    public void uploadFile(@RequestParam String folderPath, @RequestParam MultipartFile file) throws IOException {
        var user = userService.getCurrentUser();
        fileService.uploadFile(user.getRootFolderPath(), folderPath, file);
    }

    @GetMapping("/content")
    public ResponseEntity<?> getFileContent(
            @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize) throws IOException {

        // If path is null or empty, treat as listing directory/files
        if (path == null || path.isEmpty()) {
            Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("fileId").descending());
            Page<File> filePage = fileRepository.findAllFiles(pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "File(s) retrieved successfully");
            response.put("status", "success");
            response.put("data", filePage.getContent());
            response.put("totalFiles", filePage.getTotalElements());
            response.put("totalPages", filePage.getTotalPages());
            response.put("currentPage", filePage.getNumber());
            response.put("code", 200);

            return ResponseEntity.ok(response);
        }

        // Otherwise, treat as a request for a single file's content
        var user = userService.getCurrentUser();
        Path fullPath = Paths.get(user.getRootFolderPath(), path).normalize();
        folderService.validatePath(user.getRootFolderPath(), fullPath);

        if (!fullPath.toFile().exists() || !fullPath.toFile().isFile()) {
            throw new IllegalArgumentException("File does not exist: " + fullPath);
        }

        String content = fileService.readFileContent(user.getRootFolderPath(), path);
        return ResponseEntity.ok(Map.of(
                "message", "File content retrieved successfully",
                "status", "success",
                "data", content,
                "code", 200
        ));
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
    public ResponseEntity<?> listFilesForDownload(
            @RequestParam(required = false) String path,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sort) {

        Pageable pageable;
        if (sort != null && !sort.isEmpty()) {
            pageable = PageRequest.of(pageNumber, pageSize, Sort.by(sort));
        } else {
            pageable = PageRequest.of(pageNumber, pageSize, Sort.by("fileId").descending());
        }

        Page<File> filePage;

        if (path == null || path.isEmpty()) {
            filePage = fileRepository.findAllFiles(pageable);
        } else {
            filePage = fileRepository.searchAllFiles(pageable, path.toUpperCase());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "File(s) available for download");
        response.put("status", "success");
        response.put("data", filePage.getContent());
        response.put("totalFiles", filePage.getTotalElements());
        response.put("totalPages", filePage.getTotalPages());
        response.put("currentPage", filePage.getNumber());
        response.put("code", 200);

        return ResponseEntity.ok(response);
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
}