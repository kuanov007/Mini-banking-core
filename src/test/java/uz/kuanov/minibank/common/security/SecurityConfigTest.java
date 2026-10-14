package uz.kuanov.minibank.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Security rules and the 401/403 format, against a probe controller: the real admin endpoints arrive in later stages.
 * Real tokens from the app's JwtEncoder exercise the decoder and the roles-claim mapping end to end.
 */
@WebMvcTest(SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void requestWithoutTokenGets401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.instance").value("/api/probe"));
    }

    @Test
    void malformedTokenGets401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("A valid bearer token is required to access this resource."));
    }

    @Test
    void expiredTokenGets401() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(3600);
        String token = token("CUSTOMER", issuedAt, issuedAt.plusSeconds(1800));

        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void validTokenReachesProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
                .andExpect(status().isOk());
    }

    @Test
    void customerGets403ProblemDetailOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/probe").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void rolesClaimIsMappedToAuthorities() throws Exception {
        mockMvc.perform(get("/api/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accessDeniedThrownInsideControllerIs403Not500() throws Exception {
        mockMvc.perform(get("/api/probe/denied").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    private String validToken(String role) {
        Instant now = Instant.now();
        return token(role, now, now.plusSeconds(1800));
    }

    private String token(String role, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(SecurityConfig.ROLES_CLAIM, List.of(role))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        String probe() {
            return "ok";
        }

        @GetMapping("/api/admin/probe")
        String adminProbe() {
            return "ok";
        }

        @GetMapping("/api/probe/denied")
        String denied() {
            throw new AccessDeniedException("Denied by an ownership check");
        }
    }
}
