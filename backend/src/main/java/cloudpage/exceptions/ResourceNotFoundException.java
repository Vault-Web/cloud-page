package cloudpage.exceptions;

public class ResourceNotFoundException extends RuntimeException {
    String resourceName;
    String fieldName;
    String field;
    Long fieldId;

    /**
     * constructor to throw exception when ID is provided
     * @param resourceName - name of resource
     * @param fieldName - field name
     * @param field - value of field
     */
    public ResourceNotFoundException(String resourceName, String fieldName, String field) {
        super(String.format("%s not found with %s : %s", resourceName, fieldName, field));
        this.resourceName = resourceName;
        this.fieldName = fieldName;
        this.field = field;
    }

    /**
     * constructor to throw exception when ID is provided
     * @param resourceName - name of resource
     * @param field - field name
     * @param fieldId - id of field
     */
    public ResourceNotFoundException(String resourceName, String field, Long fieldId) {
        super(String.format("%s not found with %s : %d", resourceName, field, fieldId));
        this.fieldId = fieldId;
        this.resourceName = resourceName;
        this.field = field;
    }
}

