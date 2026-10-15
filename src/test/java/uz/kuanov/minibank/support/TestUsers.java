package uz.kuanov.minibank.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Registers and logs in users through the real API, for integration tests of other features. */
public final class TestUsers {

    /** The bootstrapped admin from src/test/resources/config/application.yml. */
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin-test-password";
    private static final String PASSWORD = "s3cret-password";

    private TestUsers() {
    }

    /** @param bearer the value for the Authorization header */
    public record LoggedInUser(long id, String bearer) {
    }

    /** A new customer with a unique username, so tests don't depend on each other's data. */
    public static LoggedInUser newCustomer(MockMvc mockMvc) throws Exception {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        MockHttpServletResponse registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "fullName": "Test Customer"}
                                """.formatted(username, PASSWORD)))
                .andReturn().getResponse();
        assertThat(registered.getStatus()).isEqualTo(201);
        long id = ((Number) JsonPath.read(registered.getContentAsString(), "$.id")).longValue();
        return new LoggedInUser(id, "Bearer " + login(mockMvc, username, PASSWORD));
    }

    public static LoggedInUser admin(MockMvc mockMvc) throws Exception {
        String token = login(mockMvc, ADMIN_USERNAME, ADMIN_PASSWORD);
        return new LoggedInUser(-1, "Bearer " + token);
    }

    private static String login(MockMvc mockMvc, String username, String password) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, password)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JsonPath.read(response.getContentAsString(), "$.accessToken");
    }
}
