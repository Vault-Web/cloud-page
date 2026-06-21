package cloudpage.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Optional metadata filters and sort controls for the folder search endpoint. All fields are
 * optional; an unset field is not applied. Bound from the query string of {@code GET
 * /folders/search}.
 */
@Getter
@Setter
public class SearchFilter {

  /** Restrict results to {@code "file"} or {@code "folder"}; {@code null} keeps both. */
  private String type;

  /**
   * Case-insensitive MIME-type prefix, e.g. {@code "image"} matches {@code "image/png"}. Folders
   * (which have no MIME type) are excluded when this is set.
   */
  private String mimeType;

  /** Minimum file size in bytes (inclusive). */
  private Long minSize;

  /** Maximum file size in bytes (inclusive). */
  private Long maxSize;

  /** Keep results last modified strictly after this epoch-millis timestamp. */
  private Long modifiedAfter;

  /** Keep results last modified strictly before this epoch-millis timestamp. */
  private Long modifiedBefore;

  /**
   * One of {@code "relevance"} (default), {@code "name"}, {@code "size"}, {@code "lastModified"}.
   */
  private String sortBy;

  /** Sort direction. Defaults to descending (best relevance / largest / newest first). */
  private boolean ascending;
}
