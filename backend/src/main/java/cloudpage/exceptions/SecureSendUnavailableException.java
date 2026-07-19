package cloudpage.exceptions;

/** Returned for unknown, expired, revoked, or no-longer-readable Secure Send links. */
public class SecureSendUnavailableException extends RuntimeException {

  public SecureSendUnavailableException() {
    super("Secure Send link is unavailable");
  }
}
