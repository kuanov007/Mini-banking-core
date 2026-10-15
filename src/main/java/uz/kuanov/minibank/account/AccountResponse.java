package uz.kuanov.minibank.account;

import java.math.BigDecimal;
import java.time.Instant;

/** @param balance always scale 2, serialized as a JSON number like {@code 1500.00} */
public record AccountResponse(
        Long id, String number, Currency currency, BigDecimal balance, AccountStatus status, Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getNumber(), account.getCurrency(),
                account.getBalance(), account.getStatus(), account.getCreatedAt());
    }
}
