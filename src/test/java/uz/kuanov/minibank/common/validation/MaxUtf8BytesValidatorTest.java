package uz.kuanov.minibank.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Runs through a real Bean Validation provider, so the annotation wiring and the message are checked too. */
class MaxUtf8BytesValidatorTest {

    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    record Secret(@MaxUtf8Bytes(72) String value) {
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void acceptsExactly72AsciiBytes() {
        assertThat(validator.validate(new Secret("a".repeat(72)))).isEmpty();
    }

    @Test
    void rejects73AsciiBytes() {
        assertThat(validator.validate(new Secret("a".repeat(73)))).hasSize(1);
    }

    @Test
    void countsBytesNotCharacters() {
        // 36 Cyrillic letters are 72 bytes; 37 are only 37 characters but 74 bytes
        assertThat(validator.validate(new Secret("ж".repeat(36)))).isEmpty();
        Set<ConstraintViolation<Secret>> violations = validator.validate(new Secret("ж".repeat(37)));
        assertThat(violations).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("must be at most 72 bytes in UTF-8");
    }

    @Test
    void nullIsLeftToNotNullConstraints() {
        assertThat(validator.validate(new Secret(null))).isEmpty();
    }
}
