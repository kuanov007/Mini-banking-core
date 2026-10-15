package uz.kuanov.minibank.account;

/** Account numbers are 20 digits: a value of {@code account_number_seq}, zero-padded on the left. */
final class AccountNumber {

    static final int LENGTH = 20;

    private AccountNumber() {
    }

    /** A bigint sequence tops out at 19 digits, so every positive value fits. */
    static String fromSequenceValue(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Account number sequence value must be positive: " + value);
        }
        return "%020d".formatted(value);
    }
}
