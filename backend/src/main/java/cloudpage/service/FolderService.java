package cloudpage.service;

import cloudpage.dto.FileDto;
import cloudpage.dto.FolderDto;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FolderService {

    public FolderDto getFolderTree(String rootPath) throws IOException {
        Path path = Paths.get(rootPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path does not exist or is not a directory: " + rootPath);
        }

        return readFolder(path);
    }

    private FolderDto readFolder(Path path) throws IOException {
        List<FolderDto> subfolders = Files.list(path)
                .filter(Files::isDirectory)
                .map(subPath -> {
                    try {
                        return readFolder(subPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        List<FileDto> files = Files.list(path)
                .filter(Files::isRegularFile)
                .map(filePath -> {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                        return new FileDto(
                                filePath.getFileName().toString(),
                                filePath.toAbsolutePath().toString(),
                                attrs.size(),
                                Files.probeContentType(filePath)
                        );
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        return new FolderDto(path.getFileName().toString(), path.toAbsolutePath().toString(), subfolders, files);
    }
}
