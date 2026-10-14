package uz.kuanov.minibank.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import uz.kuanov.minibank.common.security.JwtProperties;
import uz.kuanov.minibank.user.User;

class TokenServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef";

    private final SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final TokenService tokenService = new TokenService(
            NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build(),
            new JwtProperties(SECRET, Duration.ofMinutes(30)));

    @Test
    void tokenCarriesUserIdRolesAnd30MinuteLifetime() {
        User user = User.customer("azamat", "$2a$10$hash", "Azamat");
        ReflectionTestUtils.setField(user, "id", 42L); // normally assigned by the database

        TokenResponse response = tokenService.issue(user);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(1800);
    }
}
