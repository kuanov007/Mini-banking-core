package uz.kuanov.minibank.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class RequestHashTest {

    private static final String A = "00000000000000000005";
    private static final String B = "00000000000000000006";

    @Test
    void isSixtyFourLowercaseHexCharacters() {
        assertThat(hash(OperationType.TRANSFER, A, B, "10.00", "rent")).matches("[0-9a-f]{64}");
    }

    @Test
    void normalizesTheAmountScaleAndTheDescription() {
        String hash = hash(OperationType.TRANSFER, A, B, "10.00", "rent");

        assertThat(hash(OperationType.TRANSFER, A, B, "10", "rent")).isEqualTo(hash);
        assertThat(hash(OperationType.TRANSFER, A, B, "10.0", "  rent  ")).isEqualTo(hash);
        assertThat(hash(OperationType.TRANSFER, A, B, "10.00", null))
                .isEqualTo(hash(OperationType.TRANSFER, A, B, "10.00", "   "))
                .isEqualTo(hash(OperationType.TRANSFER, A, B, "10.00", ""));
    }

    @Test
    void changesWithTypeAccountsAmountAndDescription() {
        String hash = hash(OperationType.TRANSFER, A, B, "10.00", "rent");

        assertThat(hash(OperationType.TRANSFER, B, A, "10.00", "rent")).isNotEqualTo(hash);
        assertThat(hash(OperationType.TRANSFER, A, B, "10.01", "rent")).isNotEqualTo(hash);
        assertThat(hash(OperationType.TRANSFER, A, B, "10.00", "Rent")).isNotEqualTo(hash);
        // A deposit to A and a withdrawal from A with the same amount are different requests
        assertThat(hash(OperationType.DEPOSIT, null, A, "10.00", null))
                .isNotEqualTo(hash(OperationType.WITHDRAWAL, A, null, "10.00", null))
                .isNotEqualTo(hash(OperationType.TRANSFER, null, A, "10.00", null));
    }

    private static String hash(OperationType type, String from, String to, String amount, String description) {
        return RequestHash.of(type, from, to, new BigDecimal(amount), description);
    }
}
