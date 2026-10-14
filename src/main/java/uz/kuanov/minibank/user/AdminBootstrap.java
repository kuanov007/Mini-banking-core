package uz.kuanov.minibank.user;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the admin user at startup if it doesn't exist yet. Safe to run on every start. */
@Component
@EnableConfigurationProperties(AdminProperties.class)
class AdminBootstrap implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_BYTES = 72;

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    AdminBootstrap(AdminProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.username()) || !StringUtils.hasText(properties.password())) {
            log.warn("app.admin.username / app.admin.password are not set: no admin user was created");
            return;
        }
        // Same rules as customer registration; failing fast beats starting with a weak or unhashable operator password
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "app.admin.password must be at least %d characters".formatted(MIN_PASSWORD_LENGTH));
        }
        if (properties.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "app.admin.password must be at most %d bytes in UTF-8 (BCrypt limit)".formatted(MAX_PASSWORD_BYTES));
        }
        String username = User.normalizeUsername(properties.username());
        if (userRepository.existsByUsername(username)) {
            log.info("Admin user '{}' already exists", username);
            return;
        }
        User admin = userRepository.save(User.admin(username, passwordEncoder.encode(properties.password())));
        log.info("Created admin user '{}' with id {}", username, admin.getId());
    }
}
