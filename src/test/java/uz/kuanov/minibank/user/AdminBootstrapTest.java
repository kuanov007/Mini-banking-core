package uz.kuanov.minibank.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Test
    void createsAdminWithHashedPasswordWhenMissing() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("admin-password")).thenReturn("$2a$10$hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(" Admin ", "admin-password").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        bootstrap("admin", "admin-password").run(null);

        verify(userRepository).existsByUsername("admin");
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void skipsWhenNotConfigured() {
        bootstrap(null, null).run(null);

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void refusesShortPassword() {
        assertThatThrownBy(() -> bootstrap("admin", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short");
    }

    @Test
    void refusesPasswordOver72Utf8Bytes() {
        // 37 Cyrillic letters: 37 characters, 74 bytes
        assertThatThrownBy(() -> bootstrap("admin", "ж".repeat(37)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("72 bytes");
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    private AdminBootstrap bootstrap(String username, String password) {
        return new AdminBootstrap(new AdminProperties(username, password), userRepository, passwordEncoder);
    }
}
