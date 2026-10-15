package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.account.AccountService;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.support.DatabaseCleaner;
import uz.kuanov.minibank.support.LedgerInvariants;
import uz.kuanov.minibank.support.TestBank;
import uz.kuanov.minibank.support.TestBank.OpenedAccount;
import uz.kuanov.minibank.support.TestUsers;
import uz.kuanov.minibank.support.TestUsers.LoggedInUser;

/**
 * Idempotency keys against a real PostgreSQL: replays, reused keys, keys per user, and duplicates racing each other.
 * Not @Transactional, because the concurrent requests must really commit; the tables are cleaned before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class IdempotencyIT {

    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanLedger() {
        DatabaseCleaner.resetLedger(jdbc);
    }

    @AfterEach
    void ledgerInvariantsHold() {
        LedgerInvariants.assertAllHold(jdbc);
    }

    @Test
    void sameKeyTwiceInARowGivesOneOperationAndIdenticalResponses() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "1000.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        String key = newKey();

        MockHttpServletResponse first = transfer(sender, key, from, to, "250.00", "rent")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyKey.REPLAYED_HEADER))
                .andReturn().getResponse();
        // 250 and 250.00, "rent" and " rent ": the same normalized request, so still a replay
        MockHttpServletResponse second = transfer(sender, key, from, to, "250", " rent ")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyKey.REPLAYED_HEADER, "true"))
                .andReturn().getResponse();

        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(second.getHeader(HttpHeaders.LOCATION)).isEqualTo(first.getHeader(HttpHeaders.LOCATION));
        assertThat(operationsWithKey(sender.id(), key)).isEqualTo(1);
        assertThat(balance(from.id())).isEqualByComparingTo("750.00");
        assertThat(balance(to.id())).isEqualByComparingTo("250.00");
        assertThat(jdbc.sql("select request_hash from operation where idempotency_key = ?").param(key)
                .query(String.class).single()).matches("[0-9a-f]{64}");
    }

    @Test
    void depositsAndWithdrawalsAreReplayedToo() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        OpenedAccount account = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "USD");
        String depositKey = newKey();
        String withdrawalKey = newKey();

        cash("deposits", admin, depositKey, account, "100.00").andExpect(status().isCreated());
        cash("deposits", admin, depositKey, account, "100.00")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyKey.REPLAYED_HEADER, "true"));
        cash("withdrawals", admin, withdrawalKey, account, "30.00").andExpect(status().isCreated());
        cash("withdrawals", admin, withdrawalKey, account, "30.00")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyKey.REPLAYED_HEADER, "true"));

        assertThat(balance(account.id())).isEqualByComparingTo("70.00");
        assertThat(jdbc.sql("select count(*) from operation").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void sameKeyWithADifferentRequestIs422AndWritesNothing() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "1000.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        OpenedAccount otherTo = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        String key = newKey();
        transfer(sender, key, from, to, "250.00", null).andExpect(status().isCreated());
        long operationsBefore = operationCount();

        assertKeyReused(transfer(sender, key, from, to, "300.00", null));
        assertKeyReused(transfer(sender, key, from, otherTo, "250.00", null));
        assertKeyReused(transfer(sender, key, from, to, "250.00", "a different description"));

        assertThat(operationCount()).isEqualTo(operationsBefore);
        assertThat(balance(from.id())).isEqualByComparingTo("750.00");
        assertThat(balance(otherTo.id())).isEqualByComparingTo("0");
    }

    @Test
    void sameKeyForADifferentOperationTypeIs422() throws Exception {
        LoggedInUser admin = TestUsers.admin(mockMvc);
        OpenedAccount account = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        String key = newKey();
        cash("deposits", admin, key, account, "100.00").andExpect(status().isCreated());

        // Same account, amount and key, but a withdrawal is not a retry of a deposit
        assertKeyReused(cash("withdrawals", admin, key, account, "100.00"));
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void twoUsersWithTheSameKeyGetTwoSeparateOperations() throws Exception {
        LoggedInUser alice = TestUsers.newCustomer(mockMvc);
        LoggedInUser bob = TestUsers.newCustomer(mockMvc);
        OpenedAccount alicesAccount = fundedAccount(alice, "100.00");
        OpenedAccount bobsAccount = fundedAccount(bob, "100.00");
        String key = newKey();

        String alicesBody = transfer(alice, key, alicesAccount, bobsAccount, "10.00", null)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyKey.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();
        String bobsBody = transfer(bob, key, bobsAccount, alicesAccount, "10.00", null)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyKey.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(bobsBody, "$.id")).isNotEqualTo(JsonPath.read(alicesBody, "$.id"));
        assertThat(operationsWithKey(alice.id(), key)).isEqualTo(1);
        assertThat(operationsWithKey(bob.id(), key)).isEqualTo(1);
    }

    @Test
    void failedRequestWritesNothingSoARetryWithTheSameKeyRunsAgain() throws Exception {
        LoggedInUser sender = TestUsers.newCustomer(mockMvc);
        OpenedAccount from = fundedAccount(sender, "100.00");
        OpenedAccount to = TestBank.openAccount(mockMvc, TestUsers.newCustomer(mockMvc), "UZS");
        String key = newKey();

        transfer(sender, key, from, to, "150.00", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(operationsWithKey(sender.id(), key)).isZero();

        TestBank.deposit(mockMvc, TestUsers.admin(mockMvc), from.number(), "100.00");
        transfer(sender, key, from, to, "150.00", null)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyKey.REPLAYED_HEADER));
        assertThat(balance(from.id())).isEqualByComparingTo("50.00");
        assertThat(balance(to.id())).isEqualByComparingTo("150.00");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void sameRequestSentTenTimesAtOnceMovesTheMoneyOnce() throws Exception {
        long senderId = TestUsers.newCustomer(mockMvc).id();
        String from = fundedAccountOf(senderId, "1000.00");
        String to = accountService.open(TestUsers.newCustomer(mockMvc).id(), Currency.UZS).number();
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("100.00"), null);
        IdempotencyKey key = new IdempotencyKey(newKey());

        List<Object> outcomes = runAtOnce(i -> () -> ledgerService.transfer(request, senderId, key));

        assertThat(outcomes).as("results; any exception here would have been a 500 or a 422")
                .allMatch(OperationResult.class::isInstance);
        List<OperationResult> results = outcomes.stream().map(OperationResult.class::cast).toList();
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(result -> result.operation().id()).containsOnly(results.getFirst()
                .operation().id());
        assertThat(operationsWithKey(senderId, key.value())).isEqualTo(1);
        assertThat(balanceOf(from)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(to)).isEqualByComparingTo("100.00");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void differentRequestsWithTheSameKeyAtOnceGetOneOperationAndOtherwise422() throws Exception {
        long senderId = TestUsers.newCustomer(mockMvc).id();
        long receiverId = TestUsers.newCustomer(mockMvc).id();
        // Each request has its own pair of accounts: they lock different rows, so nothing queues them before the
        // insert, and the unique constraint has to settle who wins
        List<String> sources = new ArrayList<>();
        List<String> destinations = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            sources.add(fundedAccountOf(senderId, "100.00"));
            destinations.add(accountService.open(receiverId, Currency.UZS).number());
        }
        IdempotencyKey key = new IdempotencyKey(newKey());

        List<Object> outcomes = runAtOnce(i -> () -> ledgerService.transfer(
                new TransferRequest(sources.get(i), destinations.get(i), new BigDecimal("10.00"), null),
                senderId, key));

        assertThat(outcomes).filteredOn(OperationResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !(outcome instanceof OperationResult))
                .as("every loser is told the key was reused, none gets a 500")
                .hasSize(CONCURRENT_REQUESTS - 1)
                .allSatisfy(outcome -> assertThat(outcome).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(operationsWithKey(senderId, key.value())).isEqualTo(1);
        assertThat(destinations.stream().map(this::balanceOf).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10.00");
    }

    /**
     * Runs the calls on their own threads, released together; returns each call's result or the exception it threw.
     */
    private static List<Object> runAtOnce(IntFunction<Callable<OperationResult>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Future<OperationResult>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                Callable<OperationResult> call = calls.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads started").isTrue();
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<OperationResult> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    outcomes.add(ex.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertKeyReused(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(header().doesNotExist(IdempotencyKey.REPLAYED_HEADER));
    }

    private ResultActions transfer(LoggedInUser user, String key, OpenedAccount from, OpenedAccount to,
                                   String amount, String description) throws Exception {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return mockMvc.perform(post("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .header(IdempotencyKey.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccount": "%s", "toAccount": "%s", "amount": %s, "description": %s}
                        """.formatted(from.number(), to.number(), amount, descriptionJson)));
    }

    private ResultActions cash(String kind, LoggedInUser admin, String key, OpenedAccount account, String amount)
            throws Exception {
        return mockMvc.perform(post("/api/admin/" + kind)
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                .header(IdempotencyKey.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountNumber": "%s", "amount": %s}
                        """.formatted(account.number(), amount)));
    }

    private OpenedAccount fundedAccount(LoggedInUser owner, String amount) throws Exception {
        OpenedAccount account = TestBank.openAccount(mockMvc, owner, "UZS");
        TestBank.deposit(mockMvc, TestUsers.admin(mockMvc), account.number(), amount);
        return account;
    }

    /** Opens and funds a UZS account straight through the services; returns its number. */
    private String fundedAccountOf(long ownerId, String amount) {
        long adminId = jdbc.sql("select id from app_user where username = 'admin'").query(Long.class).single();
        String number = accountService.open(ownerId, Currency.UZS).number();
        ledgerService.deposit(new CashOperationRequest(number, new BigDecimal(amount), null), adminId,
                new IdempotencyKey(newKey()));
        return number;
    }

    private long operationsWithKey(long initiatorId, String key) {
        return jdbc.sql("select count(*) from operation where initiated_by = ? and idempotency_key = ?")
                .params(initiatorId, key)
                .query(Long.class).single();
    }

    private long operationCount() {
        return jdbc.sql("select count(*) from operation").query(Long.class).single();
    }

    private BigDecimal balance(long accountId) {
        return jdbc.sql("select balance from account where id = ?").param(accountId).query(BigDecimal.class).single();
    }

    private BigDecimal balanceOf(String number) {
        return jdbc.sql("select balance from account where number = ?").param(number)
                .query(BigDecimal.class).single();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
