package uz.kuanov.minibank.ledger;

public enum OperationType {
    /** Cash in at the counter: debit the SYSTEM cash account, credit the customer account. */
    DEPOSIT,
    /** Cash out at the counter: debit the customer account, credit the SYSTEM cash account. */
    WITHDRAWAL,
    /** Between two customer accounts: debit the sender, credit the receiver. */
    TRANSFER
}
