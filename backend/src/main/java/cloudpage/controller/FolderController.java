package cloudpage.controller;

import cloudpage.dto.FolderDto;
import cloudpage.service.FolderService;
import cloudpage.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;
    private final UserService userService;

    @GetMapping("/api/folders")
    public FolderDto getUserRootFolder() throws IOException {
        var user = userService.getCurrentUser();
        return folderService.getFolderTree(user.getRootFolderPath());
    }
}