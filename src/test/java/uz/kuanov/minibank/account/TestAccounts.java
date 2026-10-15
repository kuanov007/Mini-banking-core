package uz.kuanov.minibank.account;

import java.math.BigDecimal;

import org.springframework.test.util.ReflectionTestUtils;

/** Builds accounts in a given state for unit tests of any package. Ids are normally assigned by the database. */
public final class TestAccounts {

    private TestAccounts() {
    }

    public static Account customer(long id, long ownerId, Currency currency, String balance) {
        Account account = Account.openCustomerAccount("%020d".formatted(id), ownerId, currency);
        ReflectionTestUtils.setField(account, "id", id);
        BigDecimal initial = new BigDecimal(balance);
        if (initial.signum() > 0) {
            account.credit(initial);
        }
        return account;
    }

    /** SYSTEM accounts are only created by migration V3; this mimics one. */
    public static Account system(long id, Currency currency) {
        Account account = Account.openCustomerAccount("%020d".formatted(id), 0L, currency);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "ownerId", null);
        ReflectionTestUtils.setField(account, "type", AccountType.SYSTEM);
        return account;
    }

    public static Account frozen(Account account) {
        account.changeStatus(AccountStatus.FROZEN);
        return account;
    }
}
