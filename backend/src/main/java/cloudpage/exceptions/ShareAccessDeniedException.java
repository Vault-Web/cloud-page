package cloudpage.exceptions;

public class ShareAccessDeniedException extends RuntimeException {
  public ShareAccessDeniedException(String message) {
    super(message);
  }
}
