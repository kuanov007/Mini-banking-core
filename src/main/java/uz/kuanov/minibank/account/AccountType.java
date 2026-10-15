package uz.kuanov.minibank.account;

public enum AccountType {
    /** Belongs to a customer; its balance never goes below zero. */
    CUSTOMER,
    /** The bank's own cash account, one per currency, no owner; may go negative. */
    SYSTEM
}
