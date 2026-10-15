package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

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
    private static final String RECEIVER_NUMBER = "00000000000000000006";
    private static final long RECEIVER_ID = 6L;
    private static final long SENDER = 7L;
    private static final IdempotencyKey KEY = new IdempotencyKey("5f1c7b2e-8d3a-4e61-9b0f-2a7c4d9e6b13");

    @Mock
    AccountRepository accountRepository;

    @Mock
    AccountLocker accountLocker;

    @Mock
    OperationRepository operationRepository;

    /** A mock is enough: TransactionTemplate only calls getTransaction, commit and rollback on it. */
    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    LedgerService ledgerService;

    @Test
    void depositLocksBothAccountsAndCreditsTheCustomer() {
        Account cash = TestAccounts.system(CASH_ID, Currency.UZS);
        Account customer = TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "0");
        givenCustomerAccountAndCash(cash, customer);

        OperationResponse response = ledgerService.deposit(
                new CashOperationRequest(NUMBER, new BigDecimal("1500.00"), "  salary  "), 1L, KEY).operation();

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
                new CashOperationRequest(NUMBER, new BigDecimal("40.00"), " "), 1L, KEY).operation();

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

        assertThatThrownBy(() -> ledgerService.withdraw(
                new CashOperationRequest(NUMBER, new BigDecimal("10.01"), null), 1L, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownAccountNumberIsABusinessError() {
        when(accountRepository.findRefByNumber(NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.deposit(new CashOperationRequest(NUMBER, BigDecimal.TEN, null), 1L, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void systemAccountCannotBeTheTargetAndNothingIsLocked() {
        when(accountRepository.findRefByNumber(NUMBER))
                .thenReturn(Optional.of(new AccountRef(CASH_ID, AccountType.SYSTEM, Currency.UZS)));

        assertThatThrownBy(() -> ledgerService.withdraw(
                new CashOperationRequest(NUMBER, BigDecimal.TEN, null), 1L, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void transferLocksBothAccountsAndMovesTheMoney() {
        Account from = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        Account to = TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0");
        givenTransferAccounts(from, to);

        OperationResponse response = ledgerService.transfer(transfer("40.00"), SENDER, KEY).operation();

        verify(accountLocker).lockInIdOrder(CUSTOMER_ID, RECEIVER_ID);
        verify(operationRepository).saveAndFlush(any(Operation.class));
        assertThat(from.getBalance()).isEqualByComparingTo("60.00");
        assertThat(to.getBalance()).isEqualByComparingTo("40.00");
        assertThat(response.type()).isEqualTo(OperationType.TRANSFER);
        assertThat(response.fromAccount()).isEqualTo(NUMBER);
        assertThat(response.toAccount()).isEqualTo(RECEIVER_NUMBER);
    }

    @Test
    void someoneElsesSourceAccountIsNotFoundAndNothingIsLocked() {
        when(accountRepository.findRefByNumberAndOwnerId(NUMBER, SENDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.transfer(transfer("10.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Account " + NUMBER + " not found")
                .extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void unknownDestinationIsNotFoundAndNothingIsLocked() {
        when(accountRepository.findRefByNumberAndOwnerId(NUMBER, SENDER))
                .thenReturn(Optional.of(new AccountRef(CUSTOMER_ID, AccountType.CUSTOMER, Currency.UZS)));
        when(accountRepository.findRefByNumber(RECEIVER_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.transfer(transfer("10.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void systemDestinationIsRejectedAndNothingIsLocked() {
        when(accountRepository.findRefByNumberAndOwnerId(NUMBER, SENDER))
                .thenReturn(Optional.of(new AccountRef(CUSTOMER_ID, AccountType.CUSTOMER, Currency.UZS)));
        when(accountRepository.findRefByNumber(RECEIVER_NUMBER))
                .thenReturn(Optional.of(new AccountRef(CASH_ID, AccountType.SYSTEM, Currency.UZS)));

        assertThatThrownBy(() -> ledgerService.transfer(transfer("10.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SYSTEM_ACCOUNT_NOT_ALLOWED");
        verifyNoInteractions(accountLocker, operationRepository);
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        Account account = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        AccountRef ref = new AccountRef(CUSTOMER_ID, AccountType.CUSTOMER, Currency.UZS);
        when(accountRepository.findRefByNumberAndOwnerId(NUMBER, SENDER)).thenReturn(Optional.of(ref));
        when(accountRepository.findRefByNumber(NUMBER)).thenReturn(Optional.of(ref));
        when(accountLocker.lockInIdOrder(CUSTOMER_ID, CUSTOMER_ID)).thenReturn(Map.of(CUSTOMER_ID, account));

        assertThatThrownBy(() -> ledgerService.transfer(
                new TransferRequest(NUMBER, NUMBER, BigDecimal.TEN, null), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SAME_ACCOUNT");
        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void transferBetweenCurrenciesIsRejected() {
        Account from = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        Account to = TestAccounts.customer(RECEIVER_ID, 8, Currency.USD, "0");
        givenTransferAccounts(from, to);

        assertThatThrownBy(() -> ledgerService.transfer(transfer("10.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CURRENCY_MISMATCH");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void frozenDestinationIsRejected() {
        Account from = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        Account to = TestAccounts.frozen(TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0"));
        givenTransferAccounts(from, to);

        assertThatThrownBy(() -> ledgerService.transfer(transfer("10.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_NOT_ACTIVE");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameKeyAndSameRequestReplaysTheOriginalWithoutMovingMoney() {
        Account from = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        Account to = TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0");
        givenTransferAccounts(from, to);
        Operation original = earlierTransfer("40.00", null);
        when(operationRepository.findWithEntriesByInitiatorAndKey(SENDER, KEY.value()))
                .thenReturn(Optional.of(original));

        // 40 and 40.00, a missing and a blank description: the same normalized request
        OperationResult result = ledgerService.transfer(
                new TransferRequest(NUMBER, RECEIVER_NUMBER, new BigDecimal("40"), "  "), SENDER, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.operation()).isEqualTo(OperationResponse.from(original));
        assertThat(from.getBalance()).isEqualByComparingTo("100.00");
        assertThat(to.getBalance()).isEqualByComparingTo("0");
        verify(operationRepository, never()).saveAndFlush(any());
        // The key is looked up only once the accounts are locked, so a concurrent duplicate has finished by then
        InOrder order = inOrder(accountLocker, operationRepository);
        order.verify(accountLocker).lockInIdOrder(CUSTOMER_ID, RECEIVER_ID);
        order.verify(operationRepository).findWithEntriesByInitiatorAndKey(SENDER, KEY.value());
    }

    @Test
    void sameKeyWithADifferentRequestIsRejectedAndMovesNothing() {
        Account from = TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00");
        Account to = TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0");
        givenTransferAccounts(from, to);
        when(operationRepository.findWithEntriesByInitiatorAndKey(SENDER, KEY.value()))
                .thenReturn(Optional.of(earlierTransfer("50.00", null)));

        assertThatThrownBy(() -> ledgerService.transfer(transfer("40.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(from.getBalance()).isEqualByComparingTo("100.00");
        verify(operationRepository, never()).saveAndFlush(any());
    }

    @Test
    void lostRaceOnTheUniqueKeyIsResolvedByReReadingTheWinner() {
        givenTransferAccounts(TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00"),
                TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0"));
        Operation winner = earlierTransfer("40.00", null);
        // Not there when this transaction looks, committed by a concurrent one by the time it inserts
        when(operationRepository.findWithEntriesByInitiatorAndKey(SENDER, KEY.value()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(operationRepository.saveAndFlush(any(Operation.class))).thenThrow(keyViolation());

        OperationResult result = ledgerService.transfer(transfer("40.00"), SENDER, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.operation()).isEqualTo(OperationResponse.from(winner));
    }

    @Test
    void lostRaceAgainstADifferentRequestWithTheSameKeyIs422NotA500() {
        givenTransferAccounts(TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00"),
                TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0"));
        when(operationRepository.findWithEntriesByInitiatorAndKey(SENDER, KEY.value()))
                .thenReturn(Optional.empty(), Optional.of(earlierTransfer("99.00", null)));
        when(operationRepository.saveAndFlush(any(Operation.class))).thenThrow(keyViolation());

        assertThatThrownBy(() -> ledgerService.transfer(transfer("40.00"), SENDER, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void otherConstraintViolationsAreNotMistakenForAKeyConflict() {
        givenTransferAccounts(TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "100.00"),
                TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0"));
        DataIntegrityViolationException other = new DataIntegrityViolationException("check failed",
                new ConstraintViolationException("check failed", new SQLException(),
                        "ck_account_customer_balance_non_negative"));
        when(operationRepository.saveAndFlush(any(Operation.class))).thenThrow(other);

        assertThatThrownBy(() -> ledgerService.transfer(transfer("40.00"), SENDER, KEY)).isSameAs(other);
        verify(operationRepository).findWithEntriesByInitiatorAndKey(SENDER, KEY.value());
    }

    @Test
    void operationIsVisibleToParticipantsAndAdminsOnly() {
        UUID id = UUID.randomUUID();
        Operation operation = Operation.post(OperationType.DEPOSIT, TestAccounts.system(CASH_ID, Currency.UZS),
                TestAccounts.customer(CUSTOMER_ID, 7, Currency.UZS, "0"), BigDecimal.TEN, null,
                new IdempotentRequest(1L, KEY, "a".repeat(64)));
        when(operationRepository.findWithEntriesById(id)).thenReturn(Optional.of(operation));

        assertThat(ledgerService.get(id, new CurrentUser(7L, false)).type()).isEqualTo(OperationType.DEPOSIT);
        assertThat(ledgerService.get(id, new CurrentUser(1L, true)).type()).isEqualTo(OperationType.DEPOSIT);
        assertThatThrownBy(() -> ledgerService.get(id, new CurrentUser(8L, false)))
                .isInstanceOf(NotFoundException.class);
    }

    /** A TRANSFER from NUMBER to RECEIVER_NUMBER made earlier by SENDER with KEY, on its own account instances. */
    private static Operation earlierTransfer(String amount, String description) {
        IdempotentRequest earlier = new IdempotentRequest(SENDER, KEY, RequestHash.of(OperationType.TRANSFER,
                NUMBER, RECEIVER_NUMBER, new BigDecimal(amount), description));
        return Operation.post(OperationType.TRANSFER,
                TestAccounts.customer(CUSTOMER_ID, SENDER, Currency.UZS, "1000.00"),
                TestAccounts.customer(RECEIVER_ID, 8, Currency.UZS, "0"),
                new BigDecimal(amount), description, earlier);
    }

    private static DataIntegrityViolationException keyViolation() {
        return new DataIntegrityViolationException("duplicate key", new ConstraintViolationException(
                "duplicate key", new SQLException(), "uk_operation_initiator_idempotency_key"));
    }

    private static TransferRequest transfer(String amount) {
        return new TransferRequest(NUMBER, RECEIVER_NUMBER, new BigDecimal(amount), null);
    }

    /** The refs carry each account's real currency, as the database would return them. */
    private void givenTransferAccounts(Account from, Account to) {
        when(accountRepository.findRefByNumberAndOwnerId(NUMBER, SENDER))
                .thenReturn(Optional.of(new AccountRef(CUSTOMER_ID, AccountType.CUSTOMER, from.getCurrency())));
        when(accountRepository.findRefByNumber(RECEIVER_NUMBER))
                .thenReturn(Optional.of(new AccountRef(RECEIVER_ID, AccountType.CUSTOMER, to.getCurrency())));
        Map<Long, Account> locked = new LinkedHashMap<>();
        locked.put(CUSTOMER_ID, from);
        locked.put(RECEIVER_ID, to);
        when(accountLocker.lockInIdOrder(CUSTOMER_ID, RECEIVER_ID)).thenReturn(locked);
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
