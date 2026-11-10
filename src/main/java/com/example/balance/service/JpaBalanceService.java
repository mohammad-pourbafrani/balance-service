package com.example.balance.service;

import com.example.balance.account.Account;
import com.example.balance.account.AccountRepository;
import com.example.balance.error.AccountNotFoundException;
import com.example.balance.error.BalanceLimitExceededException;
import com.example.balance.error.IdempotencyConflictException;
import com.example.balance.error.InsufficientFundsException;
import com.example.balance.transaction.RejectionReason;
import com.example.balance.transaction.TransactionRecord;
import com.example.balance.transaction.TransactionRecordRepository;
import com.example.balance.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Database-backed {@link BalanceService}.
 * <p>
 * Every operation runs in a single database transaction that:
 * <ol>
 *     <li>locks the involved account rows ({@code SELECT ... FOR UPDATE}) in ascending id order,</li>
 *     <li>checks whether the transactionId was already processed,</li>
 *     <li>applies the balance change (or records a rejection),</li>
 *     <li>inserts the transaction record, whose primary key is the transactionId.</li>
 * </ol>
 * Balance changes and the idempotency record commit or roll back together. See README for the reasoning.
 */
@Service
public class JpaBalanceService implements BalanceService {

    private static final Logger log = LoggerFactory.getLogger(JpaBalanceService.class);

    private final AccountRepository accounts;
    private final TransactionRecordRepository transactions;
    private final TransactionTemplate transactionTemplate;

    public JpaBalanceService(AccountRepository accounts,
                             TransactionRecordRepository transactions,
                             PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // The re-check of the transaction record after taking the locks must see rows committed by the request
        // we were waiting on. READ COMMITTED gives us a fresh snapshot per statement; a snapshot-based level would
        // either miss that row or (on Postgres) abort with a serialization error. It's the default on both
        // Postgres and H2, but this code depends on it, so it's stated explicitly.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        execute(Operation.credit(accountId, amount, transactionId));
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        execute(Operation.debit(accountId, amount, transactionId));
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        execute(Operation.transfer(sourceAccountId, destinationAccountId, amount, transactionId));
    }

    @Override
    public long getBalance(String accountId) {
        Validation.requireId(accountId, "accountId");
        return accounts.findBalanceById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void execute(Operation operation) {
        // Fast path for retries: an already processed transaction is answered without touching account locks,
        // so a retry storm on a hot account doesn't queue up behind real work.
        TransactionRecord record = transactions.findById(operation.transactionId())
                .orElseGet(() -> applyOnce(operation));
        reportOutcome(record, operation);
    }

    private TransactionRecord applyOnce(Operation operation) {
        try {
            return transactionTemplate.execute(status -> apply(operation));
        } catch (DataIntegrityViolationException e) {
            // Duplicates of the same operation lock the same accounts, so they serialize and never get here.
            // This happens only when the same transactionId is used concurrently for operations on different
            // accounts: both passed the existence check, and the primary key let exactly one of them commit.
            // Our changes are rolled back; the winner's record decides the outcome.
            return transactions.findById(operation.transactionId()).orElseThrow(() -> e);
        }
    }

    private TransactionRecord apply(Operation operation) {
        Map<String, Account> locked = lockAccounts(operation);

        // A duplicate of this request may have committed while we were waiting for the locks.
        Optional<TransactionRecord> existing = transactions.findById(operation.transactionId());
        if (existing.isPresent()) {
            return existing.get();
        }

        RejectionReason rejection = checkRules(operation, locked);
        if (rejection != null) {
            log.info("Rejected {} {} of {}: {}", operation.type(), operation.transactionId(),
                    operation.amount(), rejection);
            return transactions.saveAndFlush(operation.rejected(rejection));
        }

        if (operation.sourceAccountId() != null) {
            locked.get(operation.sourceAccountId()).debit(operation.amount());
        }
        if (operation.destinationAccountId() != null) {
            locked.get(operation.destinationAccountId()).credit(operation.amount());
        }
        return transactions.saveAndFlush(operation.completed());
    }

    private Map<String, Account> lockAccounts(Operation operation) {
        Map<String, Account> locked = new HashMap<>();
        for (String accountId : operation.accountsInLockOrder()) {
            Account account = accounts.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            locked.put(accountId, account);
        }
        return locked;
    }

    /**
     * All rules are checked before anything is modified, so a rejected transaction never leaves
     * half-applied changes in the persistence context.
     */
    private static RejectionReason checkRules(Operation operation, Map<String, Account> locked) {
        if (operation.sourceAccountId() != null
                && !locked.get(operation.sourceAccountId()).canDebit(operation.amount())) {
            return RejectionReason.INSUFFICIENT_FUNDS;
        }
        if (operation.destinationAccountId() != null
                && !locked.get(operation.destinationAccountId()).canCredit(operation.amount())) {
            return RejectionReason.BALANCE_LIMIT_EXCEEDED;
        }
        return null;
    }

    private static void reportOutcome(TransactionRecord record, Operation operation) {
        if (!operation.isDescribedBy(record)) {
            throw new IdempotencyConflictException(operation.transactionId());
        }
        if (record.getStatus() == TransactionStatus.REJECTED) {
            throw switch (record.getRejectionReason()) {
                case INSUFFICIENT_FUNDS ->
                        new InsufficientFundsException(record.getId(), record.getSourceAccountId());
                case BALANCE_LIMIT_EXCEEDED ->
                        new BalanceLimitExceededException(record.getId(), record.getDestinationAccountId());
            };
        }
    }
}
