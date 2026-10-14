package uz.kuanov.minibank.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.user.User;
import uz.kuanov.minibank.user.UserRepository;

/**
 * Full stack against a real PostgreSQL: register, login, bearer token, admin bootstrap. Runs in `mvn verify`.
 * Tests use unique usernames instead of cleaning tables, so they don't depend on each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthApiIT {

    private static final String PASSWORD = "s3cret-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    UserRepository userRepository;

    @Test
    void registeredCustomerCanLogInAndUseTheToken() throws Exception {
        String username = uniqueUsername();
        MockHttpServletResponse registered = register(username, PASSWORD);
        assertThat(registered.getStatus()).isEqualTo(201);
        long userId = ((Number) JsonPath.read(registered.getContentAsString(), "$.id")).longValue();

        String token = login(username, PASSWORD);

        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(userId));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));

        // /actuator/info is not public: a convenient protected endpoint until the account API exists
        mockMvc.perform(get("/actuator/info").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordIsStoredAsBcryptHash() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD);

        User user = userRepository.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void passwordOfExactly72Utf8BytesWorksWithRealBcrypt() throws Exception {
        String username = uniqueUsername();
        String password = "ж".repeat(36); // 36 characters, 72 bytes: the BCrypt maximum

        assertThat(register(username, password).getStatus()).isEqualTo(201);
        login(username, password);
    }

    @Test
    void usernamesAreCaseInsensitive() throws Exception {
        String username = uniqueUsername();
        assertThat(register(username, PASSWORD).getStatus()).isEqualTo(201);

        MockHttpServletResponse duplicate = register(username.toUpperCase(), PASSWORD);

        assertThat(duplicate.getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(duplicate.getContentAsString(), "$.code")).isEqualTo("USERNAME_TAKEN");
        login(username.toUpperCase(), PASSWORD); // login is case-insensitive too
    }

    @Test
    void wrongPasswordAndUnknownUserGetIdenticalResponses() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD);

        MockHttpServletResponse wrongPassword = loginResponse(username, "wrong-password");
        MockHttpServletResponse unknownUser = loginResponse(uniqueUsername(), PASSWORD);

        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(unknownUser.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getContentAsString()).isEqualTo(unknownUser.getContentAsString());
    }

    @Test
    void concurrentRegistrationsOfOneUsernameCreateOneUserAndNo500() throws Exception {
        String username = uniqueUsername();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                statuses.add(executor.submit(() -> {
                    start.await();
                    return register(username, PASSWORD).getStatus();
                }));
            }
            start.countDown();
        }

        List<Integer> results = new ArrayList<>();
        for (Future<Integer> status : statuses) {
            results.add(status.get());
        }
        assertThat(results).containsOnly(201, 422);
        assertThat(results).filteredOn(status -> status == 201).hasSize(1);
        assertThat(userRepository.existsByUsername(username)).isTrue();
    }

    @Test
    void bootstrappedAdminCanLogInWithAdminRole() throws Exception {
        // credentials from src/test/resources/config/application.yml
        String token = login("admin", "admin-test-password");

        assertThat(jwtDecoder.decode(token).getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void healthAndApiDocsArePublicAndDocsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    private MockHttpServletResponse register(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "fullName": "Test Customer"}
                                """.formatted(username, password)))
                .andReturn().getResponse();
    }

    private String login(String username, String password) throws Exception {
        MockHttpServletResponse response = loginResponse(username, password);
        assertThat(response.getStatus()).isEqualTo(200);
        return JsonPath.read(response.getContentAsString(), "$.accessToken");
    }

    private MockHttpServletResponse loginResponse(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, password)))
                .andReturn().getResponse();
    }

    private static String uniqueUsername() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
