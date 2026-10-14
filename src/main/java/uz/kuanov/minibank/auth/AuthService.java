package uz.kuanov.minibank.auth;

import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.user.User;
import uz.kuanov.minibank.user.UserRepository;

/**
 * Registration and login. Deliberately not {@code @Transactional}: each repository call commits on its own,
 * so a unique-constraint violation surfaces here, outside the failed transaction, and can be translated.
 */
@Service
public class AuthService {

    static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    static final String USERNAME_UNIQUE_CONSTRAINT = "uk_app_user_username";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    /** Checked against when the username is unknown, so both failure paths cost one BCrypt check (no timing leak). */
    private final String dummyPasswordHash;

    AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public UserResponse register(RegisterRequest request) {
        String username = User.normalizeUsername(request.username());
        // Fast path with a friendly error; the unique constraint below is what actually guarantees uniqueness
        if (userRepository.existsByUsername(username)) {
            throw usernameTaken();
        }
        User user = User.customer(username, passwordEncoder.encode(request.password()), request.fullName().trim());
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent registrations both passed the check above; the database let only one through
            if (violates(ex, USERNAME_UNIQUE_CONSTRAINT)) {
                throw usernameTaken();
            }
            throw ex;
        }
        log.info("Registered customer with id {}", user.getId());
        return UserResponse.from(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(User.normalizeUsername(request.username())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return tokenService.issue(user);
    }

    private static BusinessException usernameTaken() {
        return new BusinessException(USERNAME_TAKEN, "This username is already taken");
    }

    private static boolean violates(DataIntegrityViolationException ex, String constraintName) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
