package cloudpage.exceptions;

/***
 * Sample class to throw File Not Found Exception
 */
public class FileNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public FileNotFoundException() {}

  public FileNotFoundException(String message) {
    super(message);
  }
}
