package cloudpage.exceptions;

/** class to handle File Deletion Exception */
public class FileDeletionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  FileDeletionException() {}

  public FileDeletionException(String message) {
    super(message);
  }
}
