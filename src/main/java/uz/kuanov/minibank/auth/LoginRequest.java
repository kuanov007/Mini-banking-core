package uz.kuanov.minibank.auth;

import jakarta.validation.constraints.NotBlank;

/** No length rules here: they would tell a caller which inputs can never be a valid password. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    /** Hides the password from logs and debuggers: a record's generated toString() prints every field. */
    @Override
    public String toString() {
        return "LoginRequest[username=%s, password=***]".formatted(username);
    }
}
