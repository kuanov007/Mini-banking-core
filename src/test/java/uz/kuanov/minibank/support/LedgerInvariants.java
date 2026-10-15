package uz.kuanov.minibank.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The double-entry invariants, checked straight in the database so they hold whatever the Java code believes.
 * Each query returns the rows that break a rule, so a failure names the offending accounts or operations.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static void assertAllHold(JdbcClient jdbc) {
        assertBalancesSumToZeroPerCurrency(jdbc);
        assertEachBalanceEqualsItsEntries(jdbc);
        assertNoNegativeCustomerBalance(jdbc);
        assertEachOperationHasOneBalancedDebitAndCredit(jdbc);
    }

    /**
     * Invariant 1: every operation moves money between two accounts, so all balances, SYSTEM included, sum to 0.
     * Checked per currency, which also implies the overall sum is 0.
     */
    public static void assertBalancesSumToZeroPerCurrency(JdbcClient jdbc) {
        List<Map<String, Object>> broken = jdbc.sql("""
                        select currency, sum(balance) as total
                        from account
                        group by currency
                        having sum(balance) <> 0
                        """)
                .query().listOfRows();
        assertThat(broken).as("currencies whose account balances don't sum to 0").isEmpty();
    }

    /** Invariant 2: each account's balance equals sum(CREDIT) - sum(DEBIT) of its entries. */
    public static void assertEachBalanceEqualsItsEntries(JdbcClient jdbc) {
        List<Map<String, Object>> broken = jdbc.sql("""
                        select a.id, a.balance,
                               coalesce(sum(case e.direction when 'CREDIT' then e.amount else -e.amount end), 0) as ledger
                        from account a
                        left join ledger_entry e on e.account_id = a.id
                        group by a.id, a.balance
                        having a.balance <> coalesce(sum(case e.direction when 'CREDIT' then e.amount else -e.amount end), 0)
                        """)
                .query().listOfRows();
        assertThat(broken).as("accounts whose balance differs from their ledger entries").isEmpty();
    }

    /** Invariant 3: no CUSTOMER balance is below 0 (SYSTEM cash accounts may be). */
    public static void assertNoNegativeCustomerBalance(JdbcClient jdbc) {
        List<Map<String, Object>> broken = jdbc.sql("""
                        select id, balance from account where type = 'CUSTOMER' and balance < 0
                        """)
                .query().listOfRows();
        assertThat(broken).as("customer accounts with a negative balance").isEmpty();
    }

    /** Every operation has exactly one DEBIT and one CREDIT, both of the operation's amount. */
    public static void assertEachOperationHasOneBalancedDebitAndCredit(JdbcClient jdbc) {
        List<Map<String, Object>> broken = jdbc.sql("""
                        select o.id, count(e.id) as entries
                        from operation o
                        left join ledger_entry e on e.operation_id = o.id
                        group by o.id, o.amount
                        having count(e.id) filter (where e.direction = 'DEBIT') <> 1
                            or count(e.id) filter (where e.direction = 'CREDIT') <> 1
                            or count(e.id) <> 2
                            or bool_or(e.amount <> o.amount)
                        """)
                .query().listOfRows();
        assertThat(broken).as("operations without exactly one DEBIT and one CREDIT of their amount").isEmpty();
    }
}
