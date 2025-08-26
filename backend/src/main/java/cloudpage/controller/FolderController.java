package cloudpage.controller;

import cloudpage.dto.FolderDto;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;
    private final UserService userService;

    @GetMapping
    public FolderDto getUserRootFolder() throws IOException {
        var user = userService.getCurrentUser();
        return folderService.getFolderTree(user.getRootFolderPath());
    }

    @GetMapping("/path")
    public FolderDto getFolderByPath(@RequestParam String path) throws IOException {
        var user = userService.getCurrentUser();
        return folderService.getFolderTree(user.getRootFolderPath(), path);
    }

    @PostMapping
    public FolderDto createFolder(@RequestParam String parentPath, @RequestParam String name) throws IOException {
        var user = userService.getCurrentUser();
        folderService.createFolder(user.getRootFolderPath(), parentPath, name);
        return folderService.getFolderTree(user.getRootFolderPath());
    }

    @DeleteMapping
    public FolderDto deleteFolder(@RequestParam String folderPath) throws IOException {
        var user = userService.getCurrentUser();
        folderService.deleteFolder(user.getRootFolderPath(), folderPath);
        return folderService.getFolderTree(user.getRootFolderPath());
    }

    @PatchMapping
    public FolderDto renameOrMoveFolder(@RequestParam String folderPath, @RequestParam String newPath) throws IOException {
        var user = userService.getCurrentUser();
        folderService.renameOrMoveFolder(user.getRootFolderPath(), folderPath, newPath);
        return folderService.getFolderTree(user.getRootFolderPath());
    }
}