package uz.kuanov.minibank.common.error;

/** Thrown when a requested resource does not exist. Mapped to 404 by {@link GlobalExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super("%s with id %s not found".formatted(resource, id));
    }
}
