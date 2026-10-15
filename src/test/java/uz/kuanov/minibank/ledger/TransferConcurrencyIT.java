package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.AccountResponse;
import uz.kuanov.minibank.account.AccountService;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.support.LedgerInvariants;
import uz.kuanov.minibank.support.TestUsers;

/**
 * Many transfers at once between a few accounts, in both directions, straight through the service. Without row
 * locks, concurrent read-modify-write would lose updates and create or destroy money; with locks taken in arbitrary
 * order, opposite transfers would deadlock. Neither may happen: the only acceptable failure is INSUFFICIENT_FUNDS.
 * Deliberately not @Transactional: every transfer must commit on its own, as it does in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TransferConcurrencyIT {

    private static final Logger log = LoggerFactory.getLogger(TransferConcurrencyIT.class);

    private static final int ACCOUNTS = 10;
    private static final int TRANSFERS = 1_000;
    private static final int THREADS = 32;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000.00");
    /** Up to 300 000.00 per transfer: large enough that some accounts run dry and INSUFFICIENT_FUNDS happens. */
    private static final int MAX_AMOUNT_IN_TIYIN = 30_000_000;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    JdbcClient jdbc;

    private record TestAccount(long id, String number, long ownerId) {
    }

    private record PlannedTransfer(TestAccount from, TestAccount to, BigDecimal amount) {
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void concurrentTransfersInBothDirectionsNeitherLoseNorCreateMoney() throws Exception {
        List<TestAccount> accounts = fundedAccounts();
        List<Long> accountIds = accounts.stream().map(TestAccount::id).toList();
        long seed = System.nanoTime();
        List<PlannedTransfer> plan = randomTransfers(accounts, new Random(seed));

        AtomicInteger next = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        Queue<Exception> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                workers.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = next.getAndIncrement(); i < TRANSFERS; i = next.getAndIncrement()) {
                        PlannedTransfer transfer = plan.get(i);
                        try {
                            ledgerService.transfer(new TransferRequest(transfer.from().number(),
                                    transfer.to().number(), transfer.amount(), null), transfer.from().ownerId());
                            succeeded.incrementAndGet();
                        } catch (BusinessException ex) {
                            if (Account.INSUFFICIENT_FUNDS.equals(ex.getCode())) {
                                insufficientFunds.incrementAndGet();
                            } else {
                                unexpected.add(ex);
                            }
                        } catch (Exception ex) {
                            unexpected.add(ex);
                        }
                    }
                    return null;
                }));
            }
            // Every worker is waiting on the start latch, so all 32 hit the database at the same moment
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all workers started").isTrue();
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("{} transfers on {} threads: {} succeeded, {} INSUFFICIENT_FUNDS (seed {})",
                TRANSFERS, THREADS, succeeded.get(), insufficientFunds.get(), seed);
        assertThat(unexpected).as("failures other than INSUFFICIENT_FUNDS (seed %d)", seed).isEmpty();
        assertThat(succeeded.get() + insufficientFunds.get()).isEqualTo(TRANSFERS);
        assertThat(succeeded.get()).as("successful transfers").isPositive();

        LedgerInvariants.assertAllHold(jdbc);
        BigDecimal total = jdbc.sql("select sum(balance) from account where id in (:ids)")
                .param("ids", accountIds)
                .query(BigDecimal.class).single();
        assertThat(total).isEqualByComparingTo(INITIAL_BALANCE.multiply(BigDecimal.valueOf(ACCOUNTS)));
        long transferOperations = jdbc.sql("""
                        select count(*) from operation o
                        join ledger_entry debit on debit.operation_id = o.id and debit.direction = 'DEBIT'
                        join ledger_entry credit on credit.operation_id = o.id and credit.direction = 'CREDIT'
                        where o.type = 'TRANSFER' and debit.account_id in (:ids) and credit.account_id in (:ids)
                        """)
                .param("ids", accountIds)
                .query(Long.class).single();
        assertThat(transferOperations).as("TRANSFER operations between the test accounts")
                .isEqualTo(succeeded.get());
    }

    /** Each account has its own owner, since a transfer must start from one of the caller's accounts. */
    private List<TestAccount> fundedAccounts() throws Exception {
        long adminId = jdbc.sql("select id from app_user where username = 'admin'").query(Long.class).single();
        List<TestAccount> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            long ownerId = TestUsers.newCustomer(mockMvc).id();
            AccountResponse account = accountService.open(ownerId, Currency.UZS);
            ledgerService.deposit(new CashOperationRequest(account.number(), INITIAL_BALANCE, null), adminId);
            accounts.add(new TestAccount(account.id(), account.number(), ownerId));
        }
        return accounts;
    }

    /** Random ordered pairs: money flows both ways between the same two accounts, so their locks are contested. */
    private static List<PlannedTransfer> randomTransfers(List<TestAccount> accounts, Random random) {
        List<PlannedTransfer> plan = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int from = random.nextInt(ACCOUNTS);
            int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(MAX_AMOUNT_IN_TIYIN), 2);
            plan.add(new PlannedTransfer(accounts.get(from), accounts.get(to), amount));
        }
        return plan;
    }
}
