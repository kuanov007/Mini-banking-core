package uz.kuanov.minibank.account;

import jakarta.validation.constraints.NotNull;

public record ChangeAccountStatusRequest(@NotNull AccountStatus status) {
}
