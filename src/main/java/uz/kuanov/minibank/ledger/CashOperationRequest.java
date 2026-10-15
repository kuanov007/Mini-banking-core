package uz.kuanov.minibank.ledger;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** A cash deposit or withdrawal at the counter, made by an operator for a customer account. */
public record CashOperationRequest(
        @NotBlank @Pattern(regexp = "\\d{20}", message = "must be 20 digits") String accountNumber,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) @DecimalMax(OperationLimits.MAX_AMOUNT)
        BigDecimal amount,
        @Size(max = 140) String description) {
}
