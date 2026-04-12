package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class SearchResult {
  private String name;
  private String path;
  private String type;
  private Long size;
  private String mimeType;
  private int score;
}
