package uz.kuanov.minibank.ledger;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** A transfer from one of the caller's accounts to any customer account, both given by account number. */
public record TransferRequest(
        @NotBlank @Pattern(regexp = "\\d{20}", message = "must be 20 digits") String fromAccount,
        @NotBlank @Pattern(regexp = "\\d{20}", message = "must be 20 digits") String toAccount,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) @DecimalMax(OperationLimits.MAX_AMOUNT)
        BigDecimal amount,
        @Size(max = 140) String description) {
}
