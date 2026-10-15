package uz.kuanov.minibank.ledger;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.AccountLocker;
import uz.kuanov.minibank.account.AccountRef;
import uz.kuanov.minibank.account.AccountRepository;
import uz.kuanov.minibank.account.AccountType;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;

/**
 * Money movements. Each one runs in a single transaction: lock the accounts in ascending id order, apply the
 * debit and the credit, insert the operation and its two entries. Any failure rolls all of it back.
 */
@Service
@Transactional(readOnly = true)
public class LedgerService {

    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final AccountLocker accountLocker;
    private final OperationRepository operationRepository;

    public LedgerService(AccountRepository accountRepository, AccountLocker accountLocker,
                         OperationRepository operationRepository) {
        this.accountRepository = accountRepository;
        this.accountLocker = accountLocker;
        this.operationRepository = operationRepository;
    }

    /** Cash in: debit the bank's cash account, credit the customer account. */
    @Transactional
    public OperationResponse deposit(CashOperationRequest request, long operatorId) {
        AccountRef customer = customerAccount(request.accountNumber());
        long cashId = cashAccountId(customer.currency());
        Map<Long, Account> locked = accountLocker.lockInIdOrder(customer.id(), cashId);
        return record(Operation.post(OperationType.DEPOSIT, locked.get(cashId), locked.get(customer.id()),
                request.amount(), description(request.description()), operatorId));
    }

    /** Cash out: debit the customer account, credit the bank's cash account. */
    @Transactional
    public OperationResponse withdraw(CashOperationRequest request, long operatorId) {
        AccountRef customer = customerAccount(request.accountNumber());
        long cashId = cashAccountId(customer.currency());
        Map<Long, Account> locked = accountLocker.lockInIdOrder(customer.id(), cashId);
        return record(Operation.post(OperationType.WITHDRAWAL, locked.get(customer.id()), locked.get(cashId),
                request.amount(), description(request.description()), operatorId));
    }

    /** An admin sees any operation; a customer only those touching one of their accounts. Anything else is 404. */
    public OperationResponse get(UUID id, CurrentUser user) {
        return operationRepository.findWithEntriesById(id)
                .filter(operation -> user.admin() || operation.involves(user.id()))
                .map(OperationResponse::from)
                .orElseThrow(() -> new NotFoundException("Operation", id));
    }

    private OperationResponse record(Operation operation) {
        // Flush now so a database constraint failure surfaces before the "completed" log line
        operationRepository.saveAndFlush(operation);
        log.info("Completed {} operation {}", operation.getType(), operation.getId());
        return OperationResponse.from(operation);
    }

    /** Number, type and currency never change, so reading them without a lock is safe. */
    private AccountRef customerAccount(String number) {
        AccountRef account = accountRepository.findRefByNumber(number)
                .orElseThrow(() -> new BusinessException(ACCOUNT_NOT_FOUND, "Account " + number + " not found"));
        if (account.type() != AccountType.CUSTOMER) {
            throw new BusinessException(Account.SYSTEM_ACCOUNT_NOT_ALLOWED,
                    "Deposits and withdrawals work only with customer accounts");
        }
        return account;
    }

    private long cashAccountId(Currency currency) {
        return accountRepository.findIdByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> new IllegalStateException("No SYSTEM cash account for " + currency));
    }

    private static String description(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }
}
