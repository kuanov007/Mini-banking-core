package uz.kuanov.minibank.account;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks every account an operation touches with {@code SELECT ... FOR UPDATE}, always in ascending id order.
 * The fixed order is what prevents deadlocks: two operations on the same pair of accounts queue up on the lower id
 * instead of each holding one lock and waiting for the other's.
 */
@Component
public class AccountLocker {

    private final AccountRepository accountRepository;

    AccountLocker(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** Locks are held until the caller's transaction ends, so there must be one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Account> lockInIdOrder(long... ids) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (long id : Arrays.stream(ids).sorted().distinct().toArray()) {
            // One statement per account makes the lock order explicit, independent of the query plan
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("Account " + id + " disappeared before locking"));
            locked.put(id, account);
        }
        return locked;
    }
}
