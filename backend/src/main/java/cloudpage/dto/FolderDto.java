package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class FolderDto {
    private String name;
    private String path;
    private List<FolderDto> folders;
    private List<FileDto> files;
}