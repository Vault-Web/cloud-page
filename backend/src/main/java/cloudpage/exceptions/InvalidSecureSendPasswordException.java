package cloudpage.exceptions;

public class InvalidSecureSendPasswordException extends RuntimeException {

  public InvalidSecureSendPasswordException() {
    super("A valid Secure Send password is required");
  }
}
