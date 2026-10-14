package uz.kuanov.minibank.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50, updatable = false)
    private String username;

    /** BCrypt hash; the raw password is never stored or logged. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    private User(String username, String passwordHash, String fullName, Role role) {
        this.username = normalizeUsername(username);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        // PostgreSQL stores microseconds: truncate so the value is identical after a database round trip
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static User customer(String username, String passwordHash, String fullName) {
        return new User(username, passwordHash, fullName, Role.CUSTOMER);
    }

    public static User admin(String username, String passwordHash) {
        return new User(username, passwordHash, "Administrator", Role.ADMIN);
    }

    /** Usernames are case-insensitive: stored and looked up in lower case. */
    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
