package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.AccountLocker;
import uz.kuanov.minibank.account.AccountRef;
import uz.kuanov.minibank.account.AccountRepository;
import uz.kuanov.minibank.account.AccountType;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.account.TestAccounts;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;

/** Pure unit test: no Spring context, no database. */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final String NUMBER = "00000000000000000005";
    private static final long CASH_ID = 1L;
    private static final long CUSTOMER_ID = 5L;

    @Mock
    AccountRepository accountRepository;

    @Mock
    AccountLocker accountLocker;

    @Mock
    OperationRepository operationRepository;

    @InjectMocks
    LedgerService ledgerService;

    @Test
    void depositLocksBothAccountsAndCreditsTheCustomer() {
        Account cash = TestAccounts.system(CASH_ID, Currency.UZS);
        Account customer = TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "0");
        givenCustomerAccountAndCash(cash, customer);

        OperationResponse response = ledgerService.deposit(
                new CashOperationRequest(NUMBER, new BigDecimal("1500.00"), "  salary  "), 1L);

        verify(accountLocker).lockInIdOrder(CUSTOMER_ID, CASH_ID);
        verify(operationRepository).saveAndFlush(any(Operation.class));
        assertThat(customer.getBalance()).isEqualByComparingTo("1500.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("-1500.00");
        assertThat(response.type()).isEqualTo(OperationType.DEPOSIT);
        assertThat(response.fromAccount()).isNull();
        assertThat(response.toAccount()).isEqualTo(customer.getNumber());
        assertThat(response.description()).isEqualTo("salary");
    }

    @Test
    void withdrawalDebitsTheCustomerAndHidesTheCashSide() {
        Account cash = TestAccounts.system(CASH_ID, Currency.UZS);
        Account customer = TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "100.00");
        givenCustomerAccountAndCash(cash, customer);

        OperationResponse response = ledgerService.withdraw(
                new CashOperationRequest(NUMBER, new BigDecimal("40.00"), " "), 1L);

        assertThat(customer.getBalance()).isEqualByComparingTo("60.00");
        assertThat(cash.getBalance()).isEqualByComparingTo("40.00");
        assertThat(response.fromAccount()).isEqualTo(customer.getNumber());
        assertThat(response.toAccount()).isNull();
        assertThat(response.description()).isNull();
    }

    @Test
    void insufficientFundsSavesNothing() {
        givenCustomerAccountAndCash(TestAccounts.system(CASH_ID, Currency.UZS),
                TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "10.00"));

        assertThatThrownBy(() -> ledgerService.withdraw(new CashOperationRequest(NUMBER, new BigDecimal("10.01"), null), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownAccountNumberIsABusinessError() {
        when(accountRepository.findRefByNumber(NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.deposit(new CashOperationRequest(NUMBER, BigDecimal.TEN, null), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void systemAccountCannotBeTheTargetAndNothingIsLocked() {
        when(accountRepository.findRefByNumber(NUMBER))
                .thenReturn(Optional.of(new AccountRef(CASH_ID, AccountType.SYSTEM, Currency.UZS)));

        assertThatThrownBy(() -> ledgerService.withdraw(new CashOperationRequest(NUMBER, BigDecimal.TEN, null), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void operationIsVisibleToParticipantsAndAdminsOnly() {
        UUID id = UUID.randomUUID();
        Operation operation = Operation.post(OperationType.DEPOSIT, TestAccounts.system(CASH_ID, Currency.UZS),
                TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "0"), BigDecimal.TEN, null, 1L);
        when(operationRepository.findWithEntriesById(id)).thenReturn(Optional.of(operation));

        assertThat(ledgerService.get(id, new CurrentUser(7L, false)).type()).isEqualTo(OperationType.DEPOSIT);
        assertThat(ledgerService.get(id, new CurrentUser(1L, true)).type()).isEqualTo(OperationType.DEPOSIT);
        assertThatThrownBy(() -> ledgerService.get(id, new CurrentUser(8L, false)))
                .isInstanceOf(NotFoundException.class);
    }

    private void givenCustomerAccountAndCash(Account cash, Account customer) {
        when(accountRepository.findRefByNumber(NUMBER))
                .thenReturn(Optional.of(new AccountRef(CUSTOMER_ID, AccountType.CUSTOMER, Currency.UZS)));
        when(accountRepository.findIdByTypeAndCurrency(AccountType.SYSTEM, Currency.UZS))
                .thenReturn(Optional.of(CASH_ID));
        Map<Long, Account> locked = new LinkedHashMap<>();
        locked.put(CASH_ID, cash);
        locked.put(CUSTOMER_ID, customer);
        when(accountLocker.lockInIdOrder(CUSTOMER_ID, CASH_ID)).thenReturn(locked);
    }
}
