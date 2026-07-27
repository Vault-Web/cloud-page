package cloudpage.dto;

import java.nio.file.Path;

public record SharedFolderResource(Path ownerRoot, Path folder) {}
