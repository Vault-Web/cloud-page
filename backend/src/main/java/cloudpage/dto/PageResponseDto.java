package cloudpage.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PageResponseDto<T> {

  private List<T> content;
  private long totalElements;
  private int totalPages;
  private int pageNumber;
}
