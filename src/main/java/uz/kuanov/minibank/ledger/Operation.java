package uz.kuanov.minibank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import uz.kuanov.minibank.account.Account;
import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;

/**
 * One money movement with exactly two ledger entries: a DEBIT and a CREDIT of the same amount.
 * {@link #post} is the only way to create one, so an unbalanced operation can't be built.
 */
@Entity
@Table(name = "operation")
public class Operation {

    static final String SAME_ACCOUNT = "SAME_ACCOUNT";
    static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OperationType type;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(length = 140, updatable = false)
    private String description;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private Long initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    /** Unique per initiator; {@code null} only on operations created before idempotency keys existed. */
    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the normalized request, see {@link RequestHash}; {@code null} exactly when the key is. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @OneToMany(mappedBy = "operation", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected Operation() {
        // required by JPA
    }

    private Operation(OperationType type, BigDecimal amount, Currency currency, String description,
                      IdempotentRequest request) {
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.initiatedBy = request.initiatorId();
        this.idempotencyKey = request.key().value();
        this.requestHash = request.requestHash();
        // PostgreSQL stores microseconds: truncate so the value is identical after a database round trip
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Moves {@code amount} from {@code debitAccount} to {@code creditAccount} and records both entries with the
     * balances after the move. Both accounts must already be locked by the caller's transaction.
     */
    static Operation post(OperationType type, Account debitAccount, Account creditAccount, BigDecimal amount,
                          String description, IdempotentRequest request) {
        // Within one persistence context, the same row is the same instance
        if (debitAccount == creditAccount) {
            throw new BusinessException(SAME_ACCOUNT, "Source and destination accounts must differ");
        }
        if (debitAccount.getCurrency() != creditAccount.getCurrency()) {
            throw new BusinessException(CURRENCY_MISMATCH, "Accounts are in different currencies");
        }
        // Validation allows at most 2 decimals, so this never rounds; it only normalizes 100 to 100.00
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        debitAccount.debit(money);
        creditAccount.credit(money);

        Operation operation = new Operation(type, money, debitAccount.getCurrency(), description, request);
        operation.entries.add(new LedgerEntry(operation, debitAccount, EntryDirection.DEBIT, money));
        operation.entries.add(new LedgerEntry(operation, creditAccount, EntryDirection.CREDIT, money));
        return operation;
    }

    LedgerEntry debitEntry() {
        return entry(EntryDirection.DEBIT);
    }

    LedgerEntry creditEntry() {
        return entry(EntryDirection.CREDIT);
    }

    /** True if the user owns one of the accounts this operation touched. */
    boolean involves(long userId) {
        return entries.stream().anyMatch(entry -> entry.getAccount().isOwnedBy(userId));
    }

    private LedgerEntry entry(EntryDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Operation " + id + " has no " + direction + " entry"));
    }

    public UUID getId() {
        return id;
    }

    public OperationType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public List<LedgerEntry> getEntries() {
        return List.copyOf(entries);
    }
}
