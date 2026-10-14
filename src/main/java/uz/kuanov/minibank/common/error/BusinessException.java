package uz.kuanov.minibank.common.error;

/**
 * A valid request that breaks a business rule, e.g. {@code new BusinessException("INSUFFICIENT_FUNDS", "...")}.
 * Mapped to 422 by {@link GlobalExceptionHandler}; the stable {@code code} lets clients branch without parsing text.
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
