package uz.kuanov.minibank.ledger;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The client's name for one logical request; a retry with the same key gets the original result instead of a second
 * money movement. Clients send a UUID; anything of 1-64 printable ASCII characters is accepted.
 */
public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    /** Set to "true" on a response that replays an earlier result. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** No spaces or control characters: those are client bugs, and the key ends up in the database and in headers. */
    private static final Pattern FORMAT = Pattern.compile("[\\x21-\\x7E]{1,64}");

    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
    }

    /** For controllers: a malformed header is the client's mistake, so it is a 400 like any other invalid input. */
    static IdempotencyKey fromHeader(String header) {
        if (header == null || !FORMAT.matcher(header).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    HEADER + " header must be 1-64 printable ASCII characters without spaces");
        }
        return new IdempotencyKey(header);
    }
}
