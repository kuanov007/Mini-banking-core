package uz.kuanov.minibank.ledger;

/** Sign convention: for every account, balance = sum(CREDIT) - sum(DEBIT). */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
