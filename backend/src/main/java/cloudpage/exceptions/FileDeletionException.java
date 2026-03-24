package cloudpage.exceptions;

import java.io.Serial;

/**
 * class to handle File Deletion Exception
 */
public class FileDeletionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    FileDeletionException() {}

    public FileDeletionException(String message) {
        super(message);
    }
}
