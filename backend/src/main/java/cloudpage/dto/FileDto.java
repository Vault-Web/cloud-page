package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class FileDto<T> {
    private String name;
    private String path;
    private long size;
    private String mimeType;
    private List<T> content;
    private int pageNumber;
    private long totalElements;
    private int totalPages;

    public FileDto(List<T> content, int pageNumber, long totalElements, int totalPages) {
        this.content = content;
        this.pageNumber = pageNumber;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }
   public FileDto() {
    }
    public FileDto(String name, String path, long size, String mimeType) {
    this.name = name;
    this.path = path;
    this.size = size;
    this.mimeType = mimeType;
}

    public String getName() {
    return name;
}

public void setName(String name) {
    this.name = name;
}

public String getPath() {
    return path;
}

public void setPath(String path) {
    this.path = path;
}

public long getSize() {
    return size;
}

public void setSize(long size) {
    this.size = size;
}

public String getMimeType() {
    return mimeType;
}

public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
}

public List<T> getContent() {
    return content;
}

public void setContent(List<T> content) {
    this.content = content;
}

public int getPageNumber() {
    return pageNumber;
}

public void setPageNumber(int pageNumber) {
    this.pageNumber = pageNumber;
}

public long getTotalElements() {
    return totalElements;
}

public void setTotalElements(long totalElements) {
    this.totalElements = totalElements;
}

public int getTotalPages() {
    return totalPages;
}

public void setTotalPages(int totalPages) {
    this.totalPages = totalPages;
}

}
