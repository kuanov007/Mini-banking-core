package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.support.LedgerInvariants;
import uz.kuanov.minibank.support.TestBank;
import uz.kuanov.minibank.support.TestBank.OpenedAccount;
import uz.kuanov.minibank.support.TestUsers;
import uz.kuanov.minibank.support.TestUsers.LoggedInUser;

/**
 * Transfers against a real PostgreSQL: the happy path and every error code. A rejected transfer must leave no
 * operation behind and both balances untouched; after every test the ledger invariants hold for the whole database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TransferApiIT {

    private static final String UNKNOWN_NUMBER = "99999999999999999999";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void ledgerInvariantsHold() {
        LedgerInvariants.assertAllHold(jdbc);
    }

    @Test
    void transferMovesTheMoneyAndBothSidesCanReadTheOperation() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        LoggedInUser receiver = TestUsers.newCustomer(mockMvc);
        LoggedInUser stranger = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "UZS", "1000.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, receiver, "UZS");

        String location = transfer(sender, from.number(), to.number(), "250.00", "rent")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/operations/")))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.fromAccount").value(from.number()))
                .andExpect(jsonPath("$.toAccount").value(to.number()))
                .andExpect(jsonPath("$.description").value("rent"))
                .andExpect(content().string(containsString("\"amount\":250.00")))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        UUID operationId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        assertThat(balance(from.id())).isEqualByComparingTo("750.00");
        assertThat(balance(to.id())).isEqualByComparingTo("250.00");
        assertThat(entry(operationId, from.id())).containsEntry("direction", "DEBIT");
        assertThat((BigDecimal) entry(operationId, from.id()).get("balance_after")).isEqualByComparingTo("750.00");
        assertThat(entry(operationId, to.id())).containsEntry("direction", "CREDIT");
        assertThat((BigDecimal) entry(operationId, to.id()).get("balance_after")).isEqualByComparingTo("250.00");
        assertThat(jdbc.sql("select initiated_by from operation where id = ?").param(operationId)
                .query(Long.class).single()).isEqualTo(sender.id());

        // The sender, the receiver and an admin can read it; anyone else gets the same 404 as for a missing one
        for (LoggedInUser reader : new LoggedInUser[] {sender, receiver, TestUsers.admin(mockMvc)}) {
            mockMvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, reader.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("TRANSFER"))
                    .andExpect(jsonPath("$.fromAccount").value(from.number()))
                    .andExpect(jsonPath("$.toAccount").value(to.number()));
        }
        mockMvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void wholeBalanceCanBeSentButNotOneTiyinMore() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "USD", "100.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "USD");

        assertRejected(sender, from.number(), to.number(), "100.01", "INSUFFICIENT_FUNDS");
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
        assertThat(balance(to.id())).isEqualByComparingTo("0");

        transfer(sender, from.number(), to.number(), "100.00").andExpect(status().isCreated());
        assertThat(balance(from.id())).isEqualByComparingTo("0");
        assertThat(balance(to.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void someoneElsesSourceAccountLooksExactlyLikeAMissingOne() throws Exception {
        LoggedInUser attacker = TestUsers.newCustomer(mockMvc);
        OpenedAccount attackersAccount = TestBank.openAccount(mockMvc, attacker, "UZS");
        OpenedAccount victimsAccount = fundedAccount(TestUsers.newCustomer(mockMvc), "UZS", "500.00");

        String foreign = assertRejected(attacker, victimsAccount.number(), attackersAccount.number(), "10.00",
                "ACCOUNT_NOT_FOUND");
        String missing = assertRejected(attacker, UNKNOWN_NUMBER, attackersAccount.number(), "10.00",
                "ACCOUNT_NOT_FOUND");

        // Same status, title and code; the detail differs only by the number the caller sent
        assertThat(JsonPath.<String>read(foreign, "$.title")).isEqualTo(JsonPath.read(missing, "$.title"));
        assertThat(JsonPath.<String>read(foreign, "$.detail"))
                .isEqualTo("Account %s not found", victimsAccount.number());
        assertThat(JsonPath.<String>read(missing, "$.detail")).isEqualTo("Account %s not found", UNKNOWN_NUMBER);
        assertThat(balance(victimsAccount.id())).isEqualByComparingTo("500.00");
    }

    @Test
    void systemCashAccountIsNotAllowedAsDestinationAndNotFoundAsSource() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "UZS", "100.00");
        String cashNumber = jdbc.sql("select number from account where type = 'SYSTEM' and currency = 'UZS'")
                .query(String.class).single();

        assertRejected(sender, from.number(), cashNumber, "10.00", "SYSTEM_ACCOUNT_NOT_ALLOWED");
        // Nobody owns the cash account, so as a source it is simply not one of the caller's accounts
        assertRejected(sender, cashNumber, from.number(), "10.00", "ACCOUNT_NOT_FOUND");
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void unknownDestinationIsAccountNotFound() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "UZS", "100.00");

        String body = assertRejected(sender, from.number(), UNKNOWN_NUMBER, "10.00", "ACCOUNT_NOT_FOUND");

        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("Account %s not found", UNKNOWN_NUMBER);
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void sameAccountIsRejected() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount account = fundedAccount(sender, "UZS", "100.00");

        assertRejected(sender, account.number(), account.number(), "10.00", "SAME_ACCOUNT");
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount uzs = fundedAccount(sender, "UZS", "100.00");
        OpenedAccount usd = TestBank.openAccount(mockMvc, sender, "USD");

        assertRejected(sender, uzs.number(), usd.number(), "10.00", "CURRENCY_MISMATCH");
        assertThat(balance(uzs.id())).isEqualByComparingTo("100.00");
        assertThat(balance(usd.id())).isEqualByComparingTo("0");
    }

    @Test
    void frozenSourceOrDestinationIsAccountNotActive() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "UZS", "100.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");

        TestBank.changeStatus(mockMvc, admin, to.id(), "FROZEN").andExpect(status().isOk());
        assertRejected(sender, from.number(), to.number(), "10.00", "ACCOUNT_NOT_ACTIVE");

        TestBank.changeStatus(mockMvc, admin, to.id(), "ACTIVE").andExpect(status().isOk());
        TestBank.changeStatus(mockMvc, admin, from.id(), "FROZEN").andExpect(status().isOk());
        assertRejected(sender, from.number(), to.number(), "10.00", "ACCOUNT_NOT_ACTIVE");
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
        assertThat(balance(to.id())).isEqualByComparingTo("0");

        // Unfrozen, it works again
        TestBank.changeStatus(mockMvc, admin, from.id(), "ACTIVE").andExpect(status().isOk());
        transfer(sender, from.number(), to.number(), "10.00").andExpect(status().isCreated());
    }

    @Test
    void amountAboveTheCapIsRejectedBeforeAnyAccountIsLookedUp() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "UZS", "100.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        long operationsBefore = operationCount();

        transfer(sender, from.number(), to.number(), "1000000000000.01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").value("must be less than or equal to 1000000000000.00"));
        // The cap itself passes validation and reaches the balance check
        assertRejected(sender, from.number(), to.number(), "1000000000000.00", "INSUFFICIENT_FUNDS");

        assertThat(operationCount()).isEqualTo(operationsBefore);
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void adminCannotTransferEvenBetweenExistingAccounts() throws Exception {
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(owner, "UZS", "100.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, owner, "UZS");

        transfer(TestUsers.admin(mockMvc), from.number(), to.number(), "10.00").andExpect(status().isForbidden());
        assertThat(balance(from.id())).isEqualByComparingTo("100.00");
    }

    /** Asserts a 422 with the given code and that no operation was written; returns the response body. */
    private String assertRejected(LoggedInUser user, String from, String to, String amount, String code)
            throws Exception {
        long operationsBefore = operationCount();
        String body = transfer(user, from, to, amount)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.instance").value("/api/transfers"))
                .andReturn().getResponse().getContentAsString();
        assertThat(operationCount()).isEqualTo(operationsBefore);
        return body;
    }

    private OpenedAccount fundedAccount(LoggedInUser owner, String currency, String amount) throws Exception {
        OpenedAccount account = TestBank.openAccount(mockMvc, owner, currency);
        TestBank.deposit(mockMvc, TestUsers.admin(mockMvc), account.number(), amount);
        return account;
    }

    private ResultActions transfer(LoggedInUser user, String from, String to, String amount) throws Exception {
        return transfer(user, from, to, amount, null);
    }

    private ResultActions transfer(LoggedInUser user, String from, String to, String amount, String description)
            throws Exception {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return mockMvc.perform(post("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccount": "%s", "toAccount": "%s", "amount": %s, "description": %s}
                        """.formatted(from, to, amount, descriptionJson)));
    }

    private Map<String, Object> entry(UUID operationId, long accountId) {
        return jdbc.sql("select direction, balance_after from ledger_entry where operation_id = ? and account_id = ?")
                .params(operationId, accountId)
                .query().singleRow();
    }

    private BigDecimal balance(long accountId) {
        return jdbc.sql("select balance from account where id = ?").param(accountId).query(BigDecimal.class).single();
    }

    private long operationCount() {
        return jdbc.sql("select count(*) from operation").query(Long.class).single();
    }
}
