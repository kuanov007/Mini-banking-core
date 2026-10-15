package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.support.TestUsers;
import uz.kuanov.minibank.support.TestUsers.LoggedInUser;

/** Full stack against a real PostgreSQL: open, list, get, ownership, freeze and unfreeze, database constraints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AccountApiIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void customerOpensListsAndReadsOwnAccounts() throws Exception {
        LoggedInUser customer = TestUsers.newCustomer(mockMvc);

        String location = open(customer, "UZS")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(content().string(containsString("\"balance\":0.00")))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        open(customer, "USD").andExpect(status().isCreated());

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].currency").value("UZS"))
                .andExpect(jsonPath("$[1].currency").value("USD"));

        mockMvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(matchesPattern("\\d{20}")))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void accountNumbersAreUnique20DigitStrings() throws Exception {
        LoggedInUser customer = TestUsers.newCustomer(mockMvc);

        String first = JsonPath.read(open(customer, "UZS").andReturn().getResponse().getContentAsString(), "$.number");
        String second = JsonPath.read(open(customer, "UZS").andReturn().getResponse().getContentAsString(), "$.number");

        assertThat(first).matches("\\d{20}");
        assertThat(second).matches("\\d{20}").isNotEqualTo(first);
    }

    @Test
    void anotherCustomersAccountIsNotFoundButAdminSeesIt() throws Exception {
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        LoggedInUser stranger = TestUsers.newCustomer(mockMvc);
        long accountId = openAndGetId(owner, "USD");

        mockMvc.perform(get("/api/accounts/{id}", accountId).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account with id %d not found".formatted(accountId)));
        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.admin(mockMvc).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));
    }

    @Test
    void anotherCustomersAccountLooksExactlyLikeAMissingOne() throws Exception {
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        LoggedInUser stranger = TestUsers.newCustomer(mockMvc);
        long accountId = openAndGetId(owner, "UZS");
        long missingId = accountId + 1_000_000;

        String foreign = mockMvc.perform(get("/api/accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get("/api/accounts/{id}", missingId)
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andReturn().getResponse().getContentAsString();

        // Same body apart from the id itself, so a 404 doesn't reveal that the account exists
        assertThat(foreign.replace(String.valueOf(accountId), "ID"))
                .isEqualTo(missing.replace(String.valueOf(missingId), "ID"));
    }

    @Test
    void adminFreezesAndUnfreezesAnAccount() throws Exception {
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        LoggedInUser admin = TestUsers.admin(mockMvc);
        long accountId = openAndGetId(owner, "UZS");

        changeStatus(admin, accountId, "FROZEN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mockMvc.perform(get("/api/accounts/{id}", accountId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(jsonPath("$.status").value("FROZEN"));

        changeStatus(admin, accountId, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/accounts/{id}", accountId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void customerCannotFreezeEvenTheirOwnAccount() throws Exception {
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        long accountId = openAndGetId(owner, "UZS");

        changeStatus(owner, accountId, "FROZEN").andExpect(status().isForbidden());
    }

    @Test
    void freezingAMissingAccountIs404() throws Exception {
        changeStatus(TestUsers.admin(mockMvc), Long.MAX_VALUE, "FROZEN").andExpect(status().isNotFound());
    }

    @Test
    void databaseRejectsACustomerAccountWithoutOwner() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into account (number, owner_id, type, currency)
                        values ('99999999999999999901', null, 'CUSTOMER', 'UZS')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_owner_matches_type");
    }

    @Test
    void databaseRejectsANegativeCustomerBalance() throws Exception {
        long accountId = openAndGetId(TestUsers.newCustomer(mockMvc), "UZS");

        assertThatThrownBy(() -> jdbcClient.sql("update account set balance = -0.01 where id = ?")
                        .param(accountId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_customer_balance_non_negative");
    }

    private ResultActions open(LoggedInUser customer, String currency) throws Exception {
        return mockMvc.perform(post("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "%s"}
                        """.formatted(currency)));
    }

    private long openAndGetId(LoggedInUser customer, String currency) throws Exception {
        String body = open(customer, currency)
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions changeStatus(LoggedInUser user, long accountId, String status) throws Exception {
        return mockMvc.perform(patch("/api/admin/accounts/{id}/status", accountId)
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "%s"}
                        """.formatted(status)));
    }
}
