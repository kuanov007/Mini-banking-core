package uz.kuanov.minibank.user;

public enum Role {
    /** Opens accounts, sees their own accounts and statements, transfers from their own accounts. */
    CUSTOMER,
    /** Bank operator: cash deposits and withdrawals, sees any account, freezes and unfreezes accounts. */
    ADMIN
}
