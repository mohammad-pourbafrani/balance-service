package com.example.balance.error;

/**
 * The transactionId has already been used for a different operation (different type, accounts or amount).
 * This is almost always a client bug, so we refuse loudly instead of silently returning the old result.
 */
public class IdempotencyConflictException extends BalanceException {

    public IdempotencyConflictException(String transactionId) {
        super("Transaction " + transactionId + " was already submitted with different parameters");
    }
}
