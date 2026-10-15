package uz.kuanov.minibank.ledger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.AccountLocker;
import uz.kuanov.minibank.account.AccountRef;
import uz.kuanov.minibank.account.AccountRepository;
import uz.kuanov.minibank.account.AccountType;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.error.ConstraintViolations;
import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;

/**
 * Money movements. Each one runs in a single transaction: lock the accounts in ascending id order, check the
 * idempotency key, apply the debit and the credit, insert the operation and its two entries. Any failure rolls all
 * of it back, so a request rejected with a business error leaves nothing behind and a retry with its key runs again.
 * <p>
 * The money-moving methods manage their transaction with a {@link TransactionTemplate} instead of
 * {@code @Transactional}: a unique-constraint violation on the key can only be handled after the failed transaction
 * has ended. They must not be called inside an existing transaction.
 */
@Service
public class LedgerService {

    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_operation_initiator_idempotency_key";

    private static final String CASH_ONLY_FOR_CUSTOMER_ACCOUNTS =
            "Deposits and withdrawals work only with customer accounts";

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final AccountLocker accountLocker;
    private final OperationRepository operationRepository;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public LedgerService(AccountRepository accountRepository, AccountLocker accountLocker,
                         OperationRepository operationRepository, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.accountLocker = accountLocker;
        this.operationRepository = operationRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /** Cash in: debit the bank's cash account, credit the customer account. */
    public OperationResult deposit(CashOperationRequest request, long operatorId, IdempotencyKey key) {
        IdempotentRequest idempotent = new IdempotentRequest(operatorId, key, RequestHash.of(OperationType.DEPOSIT,
                null, request.accountNumber(), request.amount(), request.description()));
        return idempotently(idempotent, () -> {
            AccountRef customer = customerAccount(request.accountNumber(), CASH_ONLY_FOR_CUSTOMER_ACCOUNTS);
            long cashId = cashAccountId(customer.currency());
            Map<Long, Account> locked = accountLocker.lockInIdOrder(customer.id(), cashId);
            return replayOrPost(idempotent, () -> Operation.post(OperationType.DEPOSIT, locked.get(cashId),
                    locked.get(customer.id()), request.amount(), description(request.description()), idempotent));
        });
    }

    /** Cash out: debit the customer account, credit the bank's cash account. */
    public OperationResult withdraw(CashOperationRequest request, long operatorId, IdempotencyKey key) {
        IdempotentRequest idempotent = new IdempotentRequest(operatorId, key, RequestHash.of(OperationType.WITHDRAWAL,
                request.accountNumber(), null, request.amount(), request.description()));
        return idempotently(idempotent, () -> {
            AccountRef customer = customerAccount(request.accountNumber(), CASH_ONLY_FOR_CUSTOMER_ACCOUNTS);
            long cashId = cashAccountId(customer.currency());
            Map<Long, Account> locked = accountLocker.lockInIdOrder(customer.id(), cashId);
            return replayOrPost(idempotent, () -> Operation.post(OperationType.WITHDRAWAL, locked.get(customer.id()),
                    locked.get(cashId), request.amount(), description(request.description()), idempotent));
        });
    }

    /**
     * From the caller's own account to any customer account. Same account, currency, status and funds are checked
     * by {@link Operation#post} and {@link Account} on the locked rows, so a concurrent freeze or debit can't slip
     * between the check and the move.
     */
    public OperationResult transfer(TransferRequest request, long senderId, IdempotencyKey key) {
        IdempotentRequest idempotent = new IdempotentRequest(senderId, key, RequestHash.of(OperationType.TRANSFER,
                request.fromAccount(), request.toAccount(), request.amount(), request.description()));
        return idempotently(idempotent, () -> {
            // Someone else's account gets the same error as a missing one, so account numbers can't be probed
            AccountRef from = accountRepository.findRefByNumberAndOwnerId(request.fromAccount(), senderId)
                    .orElseThrow(() -> accountNotFound(request.fromAccount()));
            AccountRef to = customerAccount(request.toAccount(), "Transfers can only go to a customer account");
            Map<Long, Account> locked = accountLocker.lockInIdOrder(from.id(), to.id());
            return replayOrPost(idempotent, () -> Operation.post(OperationType.TRANSFER, locked.get(from.id()),
                    locked.get(to.id()), request.amount(), description(request.description()), idempotent));
        });
    }

    /** An admin sees any operation; a customer only those touching one of their accounts. Anything else is 404. */
    @Transactional(readOnly = true)
    public OperationResponse get(UUID id, CurrentUser user) {
        return operationRepository.findWithEntriesById(id)
                .filter(operation -> user.admin() || operation.involves(user.id()))
                .map(OperationResponse::from)
                .orElseThrow(() -> new NotFoundException("Operation", id));
    }

    /**
     * Runs {@code work} in its own transaction. The work looks the key up after taking the account locks, so a
     * duplicate of the same request queues on those locks and then finds the first one's operation. Two different
     * requests with the same key may lock different accounts and both miss the lookup; the unique constraint lets only
     * one insert through, and the other is resolved here by re-reading the winner in a new transaction.
     */
    private OperationResult idempotently(IdempotentRequest request, Supplier<OperationResult> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataIntegrityViolationException ex) {
            if (!ConstraintViolations.violates(ex, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw ex;
            }
            return readOnlyTransaction.execute(status -> findByKey(request)
                    .map(previous -> replay(previous, request))
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key conflict, but no operation with the key was found", ex)));
        }
    }

    /** Must run with the accounts already locked. */
    private OperationResult replayOrPost(IdempotentRequest request, Supplier<Operation> post) {
        Optional<Operation> previous = findByKey(request);
        if (previous.isPresent()) {
            return replay(previous.get(), request);
        }
        Operation operation = post.get();
        // Flush now so a database constraint failure surfaces before the "completed" log line
        operationRepository.saveAndFlush(operation);
        log.info("Completed {} operation {}", operation.getType(), operation.getId());
        return new OperationResult(OperationResponse.from(operation), false);
    }

    private Optional<Operation> findByKey(IdempotentRequest request) {
        return operationRepository.findWithEntriesByInitiatorAndKey(request.initiatorId(), request.key().value());
    }

    /** Same key and same request: the original result. Same key, anything else different: the key was reused. */
    private static OperationResult replay(Operation previous, IdempotentRequest request) {
        if (!request.requestHash().equals(previous.getRequestHash())) {
            throw new BusinessException(IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request");
        }
        log.info("Replayed {} operation {}", previous.getType(), previous.getId());
        return new OperationResult(OperationResponse.from(previous), true);
    }

    /** Number, type and currency never change, so reading them without a lock is safe. */
    private AccountRef customerAccount(String number, String systemAccountMessage) {
        AccountRef account = accountRepository.findRefByNumber(number).orElseThrow(() -> accountNotFound(number));
        if (account.type() != AccountType.CUSTOMER) {
            throw new BusinessException(Account.SYSTEM_ACCOUNT_NOT_ALLOWED, systemAccountMessage);
        }
        return account;
    }

    private static BusinessException accountNotFound(String number) {
        return new BusinessException(ACCOUNT_NOT_FOUND, "Account " + number + " not found");
    }

    private long cashAccountId(Currency currency) {
        return accountRepository.findIdByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> new IllegalStateException("No SYSTEM cash account for " + currency));
    }

    private static String description(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }
}
