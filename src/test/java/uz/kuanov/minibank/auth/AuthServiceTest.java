package uz.kuanov.minibank.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.user.Role;
import uz.kuanov.minibank.user.User;
import uz.kuanov.minibank.user.UserRepository;

/** Pure unit test: no Spring context, no database. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String DUMMY_HASH = "$2a$10$dummy";

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenService tokenService;

    AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService = new AuthService(userRepository, passwordEncoder, tokenService);
    }

    @Test
    void registerStoresLowercaseUsernameAndHashedPassword() {
        when(passwordEncoder.encode("s3cret-password")).thenReturn("$2a$10$hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = authService.register(new RegisterRequest("Azamat", "s3cret-password", " Azamat K "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("azamat");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
        assertThat(response.fullName()).isEqualTo("Azamat K");
    }

    @Test
    void registerRejectsTakenUsername() {
        when(userRepository.existsByUsername("azamat")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("AZAMAT", "s3cret-password", "Azamat")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("USERNAME_TAKEN");
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerTranslatesUniqueViolationFromConcurrentRegistration() {
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate key", new SQLException(), "uk_app_user_username")));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("azamat", "s3cret-password", "Azamat")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void registerRethrowsOtherIntegrityViolations() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new ConstraintViolationException("check failed", new SQLException(), "ck_app_user_role"));
        when(userRepository.save(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("azamat", "s3cret-password", "Azamat")))
                .isSameAs(other);
    }

    @Test
    void loginIssuesTokenForCorrectPassword() {
        User user = User.customer("azamat", "$2a$10$hash", "Azamat");
        TokenResponse token = TokenResponse.bearer("jwt", 1800);
        when(userRepository.findByUsername("azamat")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("s3cret-password", "$2a$10$hash")).thenReturn(true);
        when(tokenService.issue(user)).thenReturn(token);

        assertThat(authService.login(new LoginRequest("Azamat", "s3cret-password"))).isEqualTo(token);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userRepository.findByUsername("azamat"))
                .thenReturn(Optional.of(User.customer("azamat", "$2a$10$hash", "Azamat")));
        when(passwordEncoder.matches("wrong-password", "$2a$10$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("azamat", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginWithUnknownUsernameStillChecksAPasswordHash() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "any-password")))
                .isInstanceOf(BadCredentialsException.class);
        // Same BCrypt cost as a wrong password, so response time doesn't reveal which usernames exist
        verify(passwordEncoder).matches(eq("any-password"), eq(DUMMY_HASH));
    }
}
