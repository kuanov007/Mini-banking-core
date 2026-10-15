package uz.kuanov.minibank.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import uz.kuanov.minibank.common.validation.MaxUtf8Bytes;

/**
 * @param password at least 8 characters and at most 72 bytes in UTF-8: BCrypt only reads 72 bytes, and Spring Security
 *                 rejects longer input. Counting bytes, not characters, keeps a long non-ASCII password a 400, not a 500.
 */
public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._-]{3,50}",
                message = "must be 3-50 characters: letters, digits, '.', '_' or '-'")
        String username,
        @NotBlank @Size(min = 8, message = "must be at least 8 characters") @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 100) String fullName) {

    /** Hides the password from logs and debuggers: a record's generated toString() prints every field. */
    @Override
    public String toString() {
        return "RegisterRequest[username=%s, password=***, fullName=%s]".formatted(username, fullName);
    }
}
