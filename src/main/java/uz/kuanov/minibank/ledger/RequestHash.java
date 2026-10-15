package uz.kuanov.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.util.StringUtils;

/**
 * SHA-256 of what a request asks for, so a reused idempotency key can be told apart from a genuine retry.
 * Normalized first: 100 and 100.00 are the same amount, and a null, blank or padded description means the same.
 */
final class RequestHash {

    private RequestHash() {
    }

    /**
     * @param fromAccount the debited account's number, {@code null} for a deposit (the bank's cash side)
     * @param toAccount   the credited account's number, {@code null} for a withdrawal (the bank's cash side)
     * @return 64 lowercase hex characters
     */
    static String of(OperationType type, String fromAccount, String toAccount, BigDecimal amount, String description) {
        // One field per line, the free-text description last, so no two different requests give the same text
        String normalized = String.join("\n",
                type.name(),
                Objects.requireNonNullElse(fromAccount, ""),
                Objects.requireNonNullElse(toAccount, ""),
                amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                StringUtils.hasText(description) ? description.trim() : "");
        return HexFormat.of().formatHex(sha256(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java platform must support SHA-256", ex);
        }
    }
}
