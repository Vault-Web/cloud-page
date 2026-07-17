package cloudpage.dto;

import java.nio.file.Path;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Internal result containing the pinned file path and its download metadata. */
@Getter
@AllArgsConstructor
public class SecureSendResource {

  private Path path;
  private FileResource fileResource;
}
