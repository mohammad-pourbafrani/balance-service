package com.example.balance.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;

/**
 * One row per transactionId. Serves two purposes:
 * <ul>
 *     <li>idempotency: the primary key guarantees a transactionId is applied at most once;</li>
 *     <li>audit: every balance change can be traced back to the operation that caused it.</li>
 * </ul>
 * Rows are written in the same database transaction as the balance change, so the two can never disagree.
 */
@Entity
@Table(name = "transaction_record")
public class TransactionRecord implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private TransactionType type;

    @Column(name = "source_account_id", updatable = false, length = 64)
    private String sourceAccountId;

    @Column(name = "destination_account_id", updatable = false, length = 64)
    private String destinationAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 16)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", updatable = false, length = 32)
    private RejectionReason rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected TransactionRecord() {
        // for JPA
    }

    private TransactionRecord(String id, TransactionType type, String sourceAccountId, String destinationAccountId,
                              long amount, TransactionStatus status, RejectionReason rejectionReason) {
        this.id = id;
        this.type = type;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.status = status;
        this.rejectionReason = rejectionReason;
        this.createdAt = Instant.now();
    }

    public static TransactionRecord completed(String id, TransactionType type, String sourceAccountId,
                                              String destinationAccountId, long amount) {
        return new TransactionRecord(id, type, sourceAccountId, destinationAccountId, amount,
                TransactionStatus.COMPLETED, null);
    }

    public static TransactionRecord rejected(String id, TransactionType type, String sourceAccountId,
                                             String destinationAccountId, long amount, RejectionReason reason) {
        return new TransactionRecord(id, type, sourceAccountId, destinationAccountId, amount,
                TransactionStatus.REJECTED, Objects.requireNonNull(reason));
    }

    /**
     * Whether this record describes exactly the given operation. Used to tell a genuine retry apart from
     * a client accidentally reusing a transactionId for something else.
     */
    public boolean describes(TransactionType type, String sourceAccountId, String destinationAccountId, long amount) {
        return this.type == type
                && Objects.equals(this.sourceAccountId, sourceAccountId)
                && Objects.equals(this.destinationAccountId, destinationAccountId)
                && this.amount == amount;
    }

    @Override
    public String getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
