package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
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
 * Deposits and withdrawals against a real PostgreSQL. After every test the ledger invariants must hold
 * for the whole database, not just for the accounts the test touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class LedgerApiIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void ledgerInvariantsHold() {
        LedgerInvariants.assertAllHold(jdbc);
    }

    @Test
    void depositCreditsTheCustomerAndDebitsTheCashAccount() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        LoggedInUser customer = TestUsers.newCustomer(mockMvc);
        OpenedAccount account = openAccount(customer, "UZS");
        BigDecimal cashBefore = cashBalance("UZS");

        String location = cash("deposits", admin, account.number(), "1500.00", "cash in at the counter")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/operations/")))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.fromAccount").value(nullValue()))
                .andExpect(jsonPath("$.toAccount").value(account.number()))
                .andExpect(jsonPath("$.description").value("cash in at the counter"))
                .andExpect(content().string(containsString("\"amount\":1500.00")))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(balance(account.id())).isEqualByComparingTo("1500.00");
        assertThat(cashBalance("UZS")).isEqualByComparingTo(cashBefore.subtract(new BigDecimal("1500.00")));

        // The Location points to a real endpoint, readable by the account owner
        mockMvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.toAccount").value(account.number()));
    }

    @Test
    void entriesRecordDirectionAmountAndBalanceAfter() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        OpenedAccount account = openAccount(TestUsers.newCustomer(mockMvc), "USD");

        cash("deposits", admin, account.number(), "100.00", null).andExpect(status().isCreated());
        String body = cash("withdrawals", admin, account.number(), "30.50", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.fromAccount").value(account.number()))
                .andExpect(jsonPath("$.toAccount").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String operationId = JsonPath.read(body, "$.id");

        var entry = jdbc.sql("""
                        select direction, amount, balance_after from ledger_entry
                        where operation_id = ? and account_id = ?
                        """)
                .params(UUID.fromString(operationId), account.id())
                .query().singleRow();
        assertThat(entry.get("direction")).isEqualTo("DEBIT");
        assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("30.50");
        assertThat((BigDecimal) entry.get("balance_after")).isEqualByComparingTo("69.50");
        assertThat(balance(account.id())).isEqualByComparingTo("69.50");
    }

    @Test
    void insufficientFundsLeavesNoOperationOrEntriesBehind() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        OpenedAccount account = openAccount(TestUsers.newCustomer(mockMvc), "UZS");
        cash("deposits", admin, account.number(), "100.00", null).andExpect(status().isCreated());
        long operationsBefore = operationCount();
        long entriesBefore = entryCount(account.id());

        cash("withdrawals", admin, account.number(), "100.01", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(operationCount()).isEqualTo(operationsBefore);
        assertThat(entryCount(account.id())).isEqualTo(entriesBefore);
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void frozenAccountRejectsBothDirectionsAndLeavesNothingBehind() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        OpenedAccount account = openAccount(TestUsers.newCustomer(mockMvc), "UZS");
        cash("deposits", admin, account.number(), "100.00", null).andExpect(status().isCreated());
        changeStatus(admin, account.id(), "FROZEN").andExpect(status().isOk());
        long operationsBefore = operationCount();

        cash("deposits", admin, account.number(), "10.00", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        cash("withdrawals", admin, account.number(), "10.00", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertThat(operationCount()).isEqualTo(operationsBefore);
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");

        // Unfrozen, it works again
        changeStatus(admin, account.id(), "ACTIVE").andExpect(status().isOk());
        cash("withdrawals", admin, account.number(), "10.00", null).andExpect(status().isCreated());
    }

    @Test
    void unknownAccountNumberIs422AccountNotFound() throws Exception {
        cash("deposits", TestUsers.admin(mockMvc), "99999999999999999999", "10.00", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void systemAccountCanBeNeitherTargetedNorFrozen() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        var cashAccount = jdbc.sql("select id, number from account where type = 'SYSTEM' and currency = 'UZS'")
                .query().singleRow();

        cash("deposits", admin, (String) cashAccount.get("number"), "10.00", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_NOT_ALLOWED"));
        changeStatus(admin, ((Number) cashAccount.get("id")).longValue(), "FROZEN")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_NOT_ALLOWED"));
    }

    @Test
    void migrationSeedsOneActiveCashAccountPerCurrency() {
        var cashAccounts = jdbc.sql("""
                        select currency, number, owner_id, status from account where type = 'SYSTEM' order by currency
                        """)
                .query().listOfRows();

        assertThat(cashAccounts).extracting(row -> row.get("currency")).containsExactly("USD", "UZS");
        assertThat(cashAccounts).allSatisfy(row -> {
            assertThat((String) row.get("number")).matches("\\d{20}");
            assertThat(row.get("owner_id")).isNull();
            assertThat(row.get("status")).isEqualTo("ACTIVE");
        });
    }

    @Test
    void operationIsVisibleToTheOwnerAndAdminButNotToOthers() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        LoggedInUser owner = TestUsers.newCustomer(mockMvc);
        LoggedInUser stranger = TestUsers.newCustomer(mockMvc);
        OpenedAccount account = openAccount(owner, "UZS");
        String body = cash("deposits", admin, account.number(), "5.00", null)
                .andReturn().getResponse().getContentAsString();
        String operationId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/operations/{id}", operationId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/operations/{id}", operationId).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/operations/{id}", operationId).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Operation with id %s not found".formatted(operationId)));
    }

    @Test
    void customerCannotDepositEvenToTheirOwnAccount() throws Exception {
        LoggedInUser customer = TestUsers.newCustomer(mockMvc);
        OpenedAccount account = openAccount(customer, "UZS");

        cash("deposits", customer, account.number(), "10.00", null).andExpect(status().isForbidden());
        assertThat(balance(account.id())).isEqualByComparingTo("0");
    }

    private OpenedAccount openAccount(LoggedInUser customer, String currency) throws Exception {
        return TestBank.openAccount(mockMvc, customer, currency);
    }

    private ResultActions cash(String kind, LoggedInUser user, String accountNumber, String amount,
                               String description) throws Exception {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return mockMvc.perform(post("/api/admin/" + kind)
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountNumber": "%s", "amount": %s, "description": %s}
                        """.formatted(accountNumber, amount, descriptionJson)));
    }

    private ResultActions changeStatus(LoggedInUser user, long accountId, String status) throws Exception {
        return TestBank.changeStatus(mockMvc, user, accountId, status);
    }

    private BigDecimal balance(long accountId) {
        return jdbc.sql("select balance from account where id = ?").param(accountId).query(BigDecimal.class).single();
    }

    private BigDecimal cashBalance(String currency) {
        return jdbc.sql("select balance from account where type = 'SYSTEM' and currency = ?")
                .param(currency).query(BigDecimal.class).single();
    }

    private long operationCount() {
        return jdbc.sql("select count(*) from operation").query(Long.class).single();
    }

    private long entryCount(long accountId) {
        return jdbc.sql("select count(*) from ledger_entry where account_id = ?")
                .param(accountId).query(Long.class).single();
    }
}
