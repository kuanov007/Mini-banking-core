package uz.kuanov.minibank.account;

/**
 * The fields of an account that never change after opening, read without a lock to find out what to lock.
 * A projection, not an entity: a managed Account loaded before {@code SELECT ... FOR UPDATE} would keep its
 * stale in-memory state after the lock, so balances and status are only ever read from locked entities.
 */
public record AccountRef(Long id, AccountType type, Currency currency) {
}
