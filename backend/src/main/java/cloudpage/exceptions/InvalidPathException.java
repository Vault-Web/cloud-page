package cloudpage.exceptions;

/**
 * class to handle Invalid Path Exception
 */
public class InvalidPathException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPathException() {}

    public InvalidPathException(String message) {
        super(message);
    }
}
