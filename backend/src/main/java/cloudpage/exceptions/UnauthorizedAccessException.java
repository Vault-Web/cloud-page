package cloudpage.exceptions;

/**
 * class to handle Unauthorized Access Exception
 */
public class UnauthorizedAccessException  extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnauthorizedAccessException() {}

    public UnauthorizedAccessException(String message) {
        super(message);
    }
}
