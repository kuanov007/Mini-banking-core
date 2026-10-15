package uz.kuanov.minibank.ledger;

/** @param replayed {@code true} if the operation was created by an earlier request with the same idempotency key */
public record OperationResult(OperationResponse operation, boolean replayed) {
}
