package uz.kuanov.minibank.account;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByOwnerIdOrderByIdAsc(long ownerId);

    /** Ownership is part of the query, so someone else's account is indistinguishable from a missing one. */
    Optional<Account> findByIdAndOwnerId(long id, long ownerId);

    /** {@code SELECT ... FOR UPDATE}: the row stays locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(long id);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumberValue();

    @Query("select new uz.kuanov.minibank.account.AccountRef(a.id, a.type, a.currency) from Account a"
            + " where a.number = :number")
    Optional<AccountRef> findRefByNumber(String number);

    /** Like {@link #findByIdAndOwnerId}: someone else's account is indistinguishable from a missing one. */
    @Query("select new uz.kuanov.minibank.account.AccountRef(a.id, a.type, a.currency) from Account a"
            + " where a.number = :number and a.ownerId = :ownerId")
    Optional<AccountRef> findRefByNumberAndOwnerId(String number, long ownerId);

    @Query("select a.id from Account a where a.type = :type and a.currency = :currency")
    Optional<Long> findIdByTypeAndCurrency(AccountType type, Currency currency);
}
