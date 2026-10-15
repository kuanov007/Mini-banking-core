package uz.kuanov.minibank.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import uz.kuanov.minibank.support.TestUsers.LoggedInUser;

/** Opens, funds and freezes accounts through the real API, for integration tests of other features. */
public final class TestBank {

    /** Money-moving POSTs need it; a fresh key per call makes every call a new request. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private TestBank() {
    }

    public record OpenedAccount(long id, String number) {
    }

    public static OpenedAccount openAccount(MockMvc mockMvc, LoggedInUser customer, String currency) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "%s"}
                                """.formatted(currency)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        String body = response.getContentAsString();
        return new OpenedAccount(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.number"));
    }

    public static void deposit(MockMvc mockMvc, LoggedInUser admin, String accountNumber, String amount)
            throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/admin/deposits")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": %s}
                                """.formatted(accountNumber, amount)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
    }

    public static ResultActions changeStatus(MockMvc mockMvc, LoggedInUser user, long accountId, String status)
            throws Exception {
        return mockMvc.perform(patch("/api/admin/accounts/{id}/status", accountId)
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "%s"}
                        """.formatted(status)));
    }
}
