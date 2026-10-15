package uz.kuanov.minibank.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.AccountType;
import uz.kuanov.minibank.account.Currency;

/**
 * @param fromAccount the debited account's number; {@code null} for a deposit (the bank's cash side)
 * @param toAccount   the credited account's number; {@code null} for a withdrawal (the bank's cash side)
 */
public record OperationResponse(UUID id, OperationType type, BigDecimal amount, Currency currency,
                                String fromAccount, String toAccount, String description, Instant createdAt) {

    static OperationResponse from(Operation operation) {
        return new OperationResponse(operation.getId(), operation.getType(), operation.getAmount(),
                operation.getCurrency(), publicNumber(operation.debitEntry().getAccount()),
                publicNumber(operation.creditEntry().getAccount()), operation.getDescription(),
                operation.getCreatedAt());
    }

    /** SYSTEM account numbers are internal bookkeeping, not something to show customers. */
    private static String publicNumber(Account account) {
        return account.getType() == AccountType.SYSTEM ? null : account.getNumber();
    }
}
