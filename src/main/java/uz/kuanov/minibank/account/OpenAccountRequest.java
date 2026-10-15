package uz.kuanov.minibank.account;

import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(@NotNull Currency currency) {
}
