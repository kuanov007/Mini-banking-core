package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import uz.kuanov.minibank.TestcontainersConfig;
import uz.kuanov.minibank.support.TestUsers;

/**
 * Why {@link AccountLocker} sorts ids. Two transactions that lock the same two accounts in opposite order deadlock,
 * and PostgreSQL aborts one of them; through AccountLocker the same two requests simply queue. A permanent test
 * instead of a one-off experiment, so the reason for the ordering can't be lost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DeadlockIT {

    /** PostgreSQL's SQLState for "deadlock detected". */
    private static final String DEADLOCK_DETECTED = "40P01";
    /** Bounds every wait, so a broken test fails instead of hanging CI. */
    private static final long WAIT_SECONDS = 30;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    AccountLocker accountLocker;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long first;
    private long second;

    @BeforeEach
    void openTwoAccounts() throws Exception {
        long ownerId = TestUsers.newCustomer(mockMvc).id();
        first = accountService.open(ownerId, Currency.UZS).id();
        second = accountService.open(ownerId, Currency.UZS).id();
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void lockingInOppositeOrderDeadlocksAndPostgresAbortsOneTransaction() throws Exception {
        // Released only once both transactions hold their first lock, so the cycle is certain, not a race
        CountDownLatch bothHoldTheirFirstLock = new CountDownLatch(2);

        List<Throwable> failures = runConcurrently(
                () -> lockOneThenTheOther(first, second, bothHoldTheirFirstLock),
                () -> lockOneThenTheOther(second, first, bothHoldTheirFirstLock));

        assertThat(failures).as("exactly one transaction is the deadlock victim").hasSize(1);
        assertThat(sqlState(failures.getFirst())).isEqualTo(DEADLOCK_DETECTED);
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void throughAccountLockerOppositeRequestsQueueInsteadOfDeadlocking() throws Exception {
        Duration hold = Duration.ofMillis(500);
        long started = System.nanoTime();

        // No latch: both lock the lower id first, so whichever comes second just waits for the first to commit
        List<Throwable> failures = runConcurrently(
                () -> lockThroughAccountLockerAndHold(first, second, hold),
                () -> lockThroughAccountLockerAndHold(second, first, hold));

        assertThat(failures).as("failed transactions").isEmpty();
        // One after the other, never side by side: each held both locks for the whole hold
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(hold.multipliedBy(2));
    }

    /** The statement AccountLocker issues, but in the caller's order instead of ascending id order. */
    private Void lockOneThenTheOther(long firstId, long secondId, CountDownLatch bothHoldTheirFirstLock) {
        return inTransaction(() -> {
            accountRepository.findByIdForUpdate(firstId).orElseThrow();
            bothHoldTheirFirstLock.countDown();
            if (!bothHoldTheirFirstLock.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The other transaction never took its first lock");
            }
            accountRepository.findByIdForUpdate(secondId).orElseThrow();
            return null;
        });
    }

    private Void lockThroughAccountLockerAndHold(long oneId, long otherId, Duration hold) {
        return inTransaction(() -> {
            assertThat(accountLocker.lockInIdOrder(oneId, otherId)).hasSize(2);
            Thread.sleep(hold);
            return null;
        });
    }

    private Void inTransaction(Callable<Void> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    /** Runs both tasks on their own threads and returns what each one threw, if anything. */
    private static List<Throwable> runConcurrently(Callable<Void> one, Callable<Void> other) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = List.of(pool.submit(one), pool.submit(other));
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    failures.add(ex.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Spring and Hibernate wrap the driver's exception; the SQLState is on the SQLException inside. */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("No SQLException in the cause chain", failure);
    }
}
