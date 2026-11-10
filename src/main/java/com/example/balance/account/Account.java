package com.example.balance.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * An account and its current balance, in minor units (e.g. cents).
 * <p>
 * This class does not guard against concurrent modification by itself; callers are expected to hold
 * a row lock on the account (see {@link AccountRepository#findByIdForUpdate}) before mutating it.
 */
@Entity
@Table(name = "account")
public class Account implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Ids are assigned by the client, so Spring Data can't tell new from existing by looking at the id.
    // Without this flag save() would turn into a merge (an extra SELECT before every INSERT).
    @Transient
    private boolean newEntity = true;

    protected Account() {
        // for JPA
    }

    private Account(String id, Instant createdAt) {
        this.id = id;
        this.balance = 0;
        this.createdAt = createdAt;
    }

    public static Account open(String id) {
        return new Account(id, Instant.now());
    }

    public boolean canDebit(long amount) {
        return balance >= amount;
    }

    public boolean canCredit(long amount) {
        return balance <= Long.MAX_VALUE - amount;
    }

    public void debit(long amount) {
        if (!canDebit(amount)) {
            throw new IllegalStateException("Debit of " + amount + " would make account " + id + " negative");
        }
        balance -= amount;
    }

    public void credit(long amount) {
        if (!canCredit(amount)) {
            throw new IllegalStateException("Credit of " + amount + " would overflow account " + id);
        }
        balance += amount;
    }

    @Override
    public String getId() {
        return id;
    }

    public long getBalance() {
        return balance;
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
