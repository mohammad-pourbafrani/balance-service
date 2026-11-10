package com.example.balance.service;

import com.example.balance.error.InvalidRequestException;
import com.example.balance.transaction.RejectionReason;
import com.example.balance.transaction.TransactionRecord;
import com.example.balance.transaction.TransactionType;

import java.util.SortedSet;
import java.util.TreeSet;

import static com.example.balance.service.Validation.requireId;
import static com.example.balance.service.Validation.requirePositiveAmount;

/**
 * A validated request to move money. Credits only have a destination, debits only a source.
 */
record Operation(TransactionType type, String sourceAccountId, String destinationAccountId, long amount,
                 String transactionId) {

    static Operation credit(String accountId, long amount, String transactionId) {
        return new Operation(TransactionType.CREDIT, null, requireId(accountId, "accountId"),
                requirePositiveAmount(amount), requireId(transactionId, "transactionId"));
    }

    static Operation debit(String accountId, long amount, String transactionId) {
        return new Operation(TransactionType.DEBIT, requireId(accountId, "accountId"), null,
                requirePositiveAmount(amount), requireId(transactionId, "transactionId"));
    }

    static Operation transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        requireId(sourceAccountId, "sourceAccountId");
        requireId(destinationAccountId, "destinationAccountId");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidRequestException("Source and destination accounts must be different");
        }
        return new Operation(TransactionType.TRANSFER, sourceAccountId, destinationAccountId,
                requirePositiveAmount(amount), requireId(transactionId, "transactionId"));
    }

    /**
     * Accounts touched by this operation, in the order their locks must be taken.
     * A single global order is what keeps concurrent transfers (A→B and B→A) from deadlocking.
     */
    SortedSet<String> accountsInLockOrder() {
        SortedSet<String> ids = new TreeSet<>();
        if (sourceAccountId != null) {
            ids.add(sourceAccountId);
        }
        if (destinationAccountId != null) {
            ids.add(destinationAccountId);
        }
        return ids;
    }

    boolean isDescribedBy(TransactionRecord record) {
        return record.describes(type, sourceAccountId, destinationAccountId, amount);
    }

    TransactionRecord completed() {
        return TransactionRecord.completed(transactionId, type, sourceAccountId, destinationAccountId, amount);
    }

    TransactionRecord rejected(RejectionReason reason) {
        return TransactionRecord.rejected(transactionId, type, sourceAccountId, destinationAccountId, amount, reason);
    }
}
