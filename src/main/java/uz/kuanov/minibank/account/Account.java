package uz.kuanov.minibank.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An account with behaviour, not a bag of setters: number, owner, type and currency are fixed at opening;
 * the status changes through {@link #changeStatus}, the balance (from Stage 3) only through ledger operations.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String number;

    /** The owner's user id; a plain id, not a @ManyToOne, so the account feature doesn't load users. */
    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected Account() {
        // required by JPA
    }

    private Account(String number, Long ownerId, AccountType type, Currency currency) {
        this.number = number;
        this.ownerId = ownerId;
        this.type = type;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.status = AccountStatus.ACTIVE;
        // PostgreSQL stores microseconds: truncate so the value is identical after a database round trip
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static Account openCustomerAccount(String number, long ownerId, Currency currency) {
        return new Account(number, ownerId, AccountType.CUSTOMER, currency);
    }

    /** Idempotent: setting the current status again changes nothing. */
    void changeStatus(AccountStatus newStatus) {
        this.status = newStatus;
    }

    boolean isOwnedBy(long userId) {
        return ownerId != null && ownerId == userId;
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public AccountType getType() {
        return type;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
