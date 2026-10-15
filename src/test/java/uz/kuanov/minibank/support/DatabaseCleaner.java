package uz.kuanov.minibank.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * For tests that can't roll back with @Transactional (concurrency tests: their work must really commit).
 * Brings the ledger back to its state right after the migrations, so counts and sums see only the current test.
 */
public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    /**
     * Deletes all operations, entries and customer accounts and zeroes the SYSTEM cash accounts, which the migration
     * seeded and other tests expect to exist. Users stay: they hold no money, and the bootstrapped admin must remain.
     */
    public static void resetLedger(JdbcClient jdbc) {
        jdbc.sql("delete from ledger_entry").update();
        jdbc.sql("delete from operation").update();
        jdbc.sql("delete from account where type = 'CUSTOMER'").update();
        jdbc.sql("update account set balance = 0 where type = 'SYSTEM'").update();
    }
}
