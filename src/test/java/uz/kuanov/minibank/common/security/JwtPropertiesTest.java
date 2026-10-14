package uz.kuanov.minibank.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** The app must refuse to start with a missing or weak signing key. */
class JwtPropertiesTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new JwtProperties(null, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("a".repeat(31), TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining("aaaa");
    }

    @Test
    void countsBytesNotCharacters() {
        // 16 Cyrillic letters are 32 bytes in UTF-8
        assertThat(new JwtProperties("ж".repeat(16), TTL).secret()).hasSize(16);
    }

    @Test
    void acceptsSecretOf32Bytes() {
        assertThat(new JwtProperties("a".repeat(32), TTL).ttl()).isEqualTo(TTL);
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new JwtProperties("a".repeat(32), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
