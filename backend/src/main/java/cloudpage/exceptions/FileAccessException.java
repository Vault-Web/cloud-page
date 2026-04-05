package cloudpage.exceptions;

/** Exception for file access and read operation failures */
public class FileAccessException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public FileAccessException() {}

  public FileAccessException(String message) {
    super(message);
  }
}
