package uz.kuanov.minibank.auth;

import java.time.Instant;

import uz.kuanov.minibank.user.Role;
import uz.kuanov.minibank.user.User;

public record UserResponse(Long id, String username, String fullName, Role role, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.getCreatedAt());
    }
}
