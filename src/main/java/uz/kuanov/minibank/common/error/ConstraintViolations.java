package uz.kuanov.minibank.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Tells which database constraint a failed write broke, for the few violations that are expected business cases. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean violates(DataIntegrityViolationException ex, String constraintName) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
