package uz.kuanov.minibank.account;

public enum AccountStatus {
    /** Can send and receive money. */
    ACTIVE,
    /** Blocked by an operator: no money moves in or out. */
    FROZEN
}
