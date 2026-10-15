package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.account.TestAccounts;
import uz.kuanov.minibank.common.error.BusinessException;

class OperationTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void depositDebitsCashCreditsCustomerAndRecordsBalancesAfter() {
        Account cash = TestAccounts.system(1, Currency.UZS);
        Account customer = TestAccounts.customer(5, 7, Currency.UZS, "100.00");

        Operation operation = Operation.post(OperationType.DEPOSIT, cash, customer, new BigDecimal("50"), "cash in",
                by(1));

        assertThat(operation.getAmount()).isEqualByComparingTo("50.00");
        assertThat(operation.getAmount().scale()).isEqualTo(2);
        assertThat(operation.getCurrency()).isEqualTo(Currency.UZS);
        assertThat(operation.getInitiatedBy()).isEqualTo(1L);
        assertThat(operation.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(operation.getRequestHash()).isEqualTo(HASH);
        assertThat(cash.getBalance()).isEqualByComparingTo("-50.00");
        assertThat(customer.getBalance()).isEqualByComparingTo("150.00");

        assertThat(operation.getEntries()).hasSize(2);
        LedgerEntry debit = operation.debitEntry();
        LedgerEntry credit = operation.creditEntry();
        assertThat(debit.getAccount()).isSameAs(cash);
        assertThat(debit.getAmount()).isEqualByComparingTo("50.00");
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("-50.00");
        assertThat(credit.getAccount()).isSameAs(customer);
        assertThat(credit.getAmount()).isEqualByComparingTo("50.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("150.00");
    }

    @Test
    void failedDebitLeavesBothAccountsUntouched() {
        Account customer = TestAccounts.customer(5, 7, Currency.UZS, "10.00");
        Account cash = TestAccounts.system(1, Currency.UZS);

        assertThatThrownBy(() -> Operation.post(OperationType.WITHDRAWAL, customer, cash, new BigDecimal("10.01"),
                null, by(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(customer.getBalance()).isEqualByComparingTo("10.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsSameAccountOnBothSides() {
        Account account = TestAccounts.customer(5, 7, Currency.UZS, "100.00");

        assertThatThrownBy(() -> Operation.post(OperationType.TRANSFER, account, account, BigDecimal.ONE, null, by(7)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SAME_ACCOUNT");
    }

    @Test
    void rejectsDifferentCurrencies() {
        Account uzs = TestAccounts.customer(5, 7, Currency.UZS, "100.00");
        Account usd = TestAccounts.customer(6, 7, Currency.USD, "0");

        assertThatThrownBy(() -> Operation.post(OperationType.TRANSFER, uzs, usd, BigDecimal.ONE, null, by(7)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CURRENCY_MISMATCH");
        assertThat(uzs.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void involvesTheOwnersOfItsAccountsOnly() {
        Operation operation = Operation.post(OperationType.DEPOSIT, TestAccounts.system(1, Currency.UZS),
                TestAccounts.customer(5, 7, Currency.UZS, "0"), BigDecimal.TEN, null, by(1));

        assertThat(operation.involves(7)).isTrue();
        assertThat(operation.involves(8)).isFalse();
    }

    private static IdempotentRequest by(long initiatorId) {
        return new IdempotentRequest(initiatorId, new IdempotencyKey("key-" + initiatorId), HASH);
    }
}
