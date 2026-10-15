package uz.kuanov.minibank.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

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
}
