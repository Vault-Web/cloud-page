package cloudpage.exceptions;

import java.io.Serial;

public class FileReadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FileReadException(String message) {
        super(message);
    }
}
