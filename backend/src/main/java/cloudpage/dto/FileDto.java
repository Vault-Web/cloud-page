package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FileDto {
    private String name;
    private String path;
    private long size;
    private String mimeType;
}
