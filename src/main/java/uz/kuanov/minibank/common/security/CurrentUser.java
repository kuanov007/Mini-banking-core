package uz.kuanov.minibank.common.security;

import org.springframework.security.core.Authentication;

/**
 * The caller, read from the validated token: {@code sub} is the user id, roles come from the granted authorities
 * (the same mapping the URL rules use), so there is one source of truth for "is this an admin".
 */
public record CurrentUser(long id, boolean admin) {

    public static CurrentUser from(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new CurrentUser(Long.parseLong(authentication.getName()), admin);
    }
}
