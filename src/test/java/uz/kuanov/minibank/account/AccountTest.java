package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import uz.kuanov.minibank.common.error.BusinessException;

class AccountTest {

    private static final String NUMBER = "00000000000000000001";

    @Test
    void newCustomerAccountIsActiveWithZeroBalanceAtScale2() {
        Account account = Account.openCustomerAccount(NUMBER, 7L, Currency.UZS);

        assertThat(account.getType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getCurrency()).isEqualTo(Currency.UZS);
        assertThat(account.getOwnerId()).isEqualTo(7L);
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getBalance().scale()).isEqualTo(2);
        assertThat(account.getCreatedAt()).isNotNull();
    }

    @Test
    void canBeFrozenAndUnfrozen() {
        Account account = Account.openCustomerAccount(NUMBER, 7L, Currency.USD);

        account.changeStatus(AccountStatus.FROZEN);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);

        account.changeStatus(AccountStatus.ACTIVE);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void changingToTheCurrentStatusIsANoOp() {
        Account account = Account.openCustomerAccount(NUMBER, 7L, Currency.USD);

        account.changeStatus(AccountStatus.ACTIVE);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void knowsItsOwner() {
        Account account = Account.openCustomerAccount(NUMBER, 7L, Currency.UZS);

        assertThat(account.isOwnedBy(7L)).isTrue();
        assertThat(account.isOwnedBy(8L)).isFalse();
    }

    @Test
    void creditAndDebitChangeTheBalance() {
        Account account = TestAccounts.customer(1, 7, Currency.UZS, "0");

        account.credit(new BigDecimal("1500.50"));
        account.debit(new BigDecimal("500.25"));

        assertThat(account.getBalance()).isEqualByComparingTo("1000.25");
        assertThat(account.getBalance().scale()).isEqualTo(2);
    }

    @Test
    void customerAccountCanBeDebitedDownToExactlyZero() {
        Account account = TestAccounts.customer(1, 7, Currency.UZS, "100.00");

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void customerAccountCannotGoNegative() {
        Account account = TestAccounts.customer(1, 7, Currency.UZS, "100.00");

        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void systemAccountMayGoNegative() {
        Account cash = TestAccounts.system(1, Currency.USD);

        cash.debit(new BigDecimal("250.00"));

        assertThat(cash.getBalance()).isEqualByComparingTo("-250.00");
    }

    @Test
    void frozenAccountCanBeNeitherDebitedNorCredited() {
        Account account = TestAccounts.frozen(TestAccounts.customer(1, 7, Currency.UZS, "100.00"));

        assertThatThrownBy(() -> account.debit(BigDecimal.ONE))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThatThrownBy(() -> account.credit(BigDecimal.ONE))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void frozenCheckComesBeforeTheFundsCheck() {
        Account account = TestAccounts.frozen(TestAccounts.customer(1, 7, Currency.UZS, "0"));

        assertThatThrownBy(() -> account.debit(BigDecimal.TEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void nonPositiveAmountIsAProgrammingError() {
        Account account = TestAccounts.customer(1, 7, Currency.UZS, "100.00");

        assertThatThrownBy(() -> account.credit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemAccountStatusCannotBeChanged() {
        Account cash = TestAccounts.system(1, Currency.UZS);

        assertThatThrownBy(() -> cash.changeStatus(AccountStatus.FROZEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        assertThat(cash.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
}
