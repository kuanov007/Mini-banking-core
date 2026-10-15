package uz.kuanov.minibank.account;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse open(long ownerId, Currency currency) {
        String number = AccountNumber.fromSequenceValue(accountRepository.nextAccountNumberValue());
        Account account = accountRepository.save(Account.openCustomerAccount(number, ownerId, currency));
        log.info("Opened {} account {} for user {}", currency, account.getId(), ownerId);
        return AccountResponse.from(account);
    }

    public List<AccountResponse> listOwn(long ownerId) {
        return accountRepository.findAllByOwnerIdOrderByIdAsc(ownerId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /** An admin sees any account; a customer only their own. Anything else is a 404, never a 403. */
    public AccountResponse get(long id, CurrentUser user) {
        return (user.admin() ? accountRepository.findById(id) : accountRepository.findByIdAndOwnerId(id, user.id()))
                .map(AccountResponse::from)
                .orElseThrow(() -> new NotFoundException("Account", id));
    }

    /**
     * Locks the row first: Hibernate's UPDATE writes every column, balance included, so without the lock
     * a freeze could overwrite a balance that a concurrent money movement has just committed.
     */
    @Transactional
    public AccountResponse changeStatus(long id, AccountStatus status) {
        Account account = accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account", id));
        AccountStatus previous = account.getStatus();
        account.changeStatus(status);
        log.info("Account {} status {} -> {}", id, previous, status);
        return AccountResponse.from(account);
    }
}
