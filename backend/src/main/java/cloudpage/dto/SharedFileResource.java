package cloudpage.dto;

import java.nio.file.Path;

public record SharedFileResource(Path path, FileResource fileResource) {}
