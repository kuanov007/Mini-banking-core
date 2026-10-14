package uz.kuanov.minibank.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings. The checks run while the properties are bound, so a missing or short secret stops the app at startup
 * instead of producing weak tokens. The secret is never part of an error message.
 *
 * @param secret HS256 signing key, env {@code APP_JWT_SECRET}; at least 32 bytes (256 bits, the HMAC-SHA256 key size)
 * @param ttl    token lifetime
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration ttl) {

    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "app.jwt.secret (env APP_JWT_SECRET) must be set and be at least %d bytes long"
                            .formatted(MIN_SECRET_BYTES));
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("app.jwt.ttl must be a positive duration");
        }
    }
}
