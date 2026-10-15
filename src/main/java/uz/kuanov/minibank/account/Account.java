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

import uz.kuanov.minibank.common.error.BusinessException;

/**
 * An account with behaviour, not a bag of setters: number, owner, type and currency are fixed at opening;
 * the status changes through {@link #changeStatus}, the balance only through {@link #debit} and {@link #credit}.
 */
@Entity
@Table(name = "account")
public class Account {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";
    /** A SYSTEM account used where only CUSTOMER accounts are allowed: cash operations, status changes. */
    public static final String SYSTEM_ACCOUNT_NOT_ALLOWED = "SYSTEM_ACCOUNT_NOT_ALLOWED";

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

    /**
     * Takes money out. Only for ledger operations, on an account locked with {@code SELECT ... FOR UPDATE}.
     * A CUSTOMER balance never goes below zero; a SYSTEM (cash) account may.
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount);
        requireActive();
        BigDecimal newBalance = balance.subtract(amount);
        if (type == AccountType.CUSTOMER && newBalance.signum() < 0) {
            throw new BusinessException(INSUFFICIENT_FUNDS, "Insufficient funds on account " + number);
        }
        this.balance = newBalance;
    }

    /** Puts money in. Only for ledger operations, on an account locked with {@code SELECT ... FOR UPDATE}. */
    public void credit(BigDecimal amount) {
        requirePositive(amount);
        requireActive();
        this.balance = balance.add(amount);
    }

    /**
     * Idempotent: setting the current status again changes nothing. A SYSTEM (cash) account can't be frozen:
     * that would stop every deposit and withdrawal in its currency.
     */
    void changeStatus(AccountStatus newStatus) {
        if (type == AccountType.SYSTEM) {
            throw new BusinessException(SYSTEM_ACCOUNT_NOT_ALLOWED, "The status of a SYSTEM account can't be changed");
        }
        this.status = newStatus;
    }

    public boolean isOwnedBy(long userId) {
        return ownerId != null && ownerId == userId;
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessException(ACCOUNT_NOT_ACTIVE, "Account " + number + " is not active");
        }
    }

    /** Amounts are validated at the API edge; a non-positive one reaching here is a bug, not a business case. */
    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
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
