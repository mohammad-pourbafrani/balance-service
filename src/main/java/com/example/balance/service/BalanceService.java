package com.example.balance.service;

/**
 * Money movements on accounts. Amounts are in minor units (e.g. cents) and must be positive.
 * <p>
 * All mutating operations are idempotent on {@code transactionId}: submitting the same operation again,
 * concurrently or later, has no further effect on balances and yields the same outcome as the first call.
 * Reusing a transactionId for a different operation fails with
 * {@link com.example.balance.error.IdempotencyConflictException}.
 */
public interface BalanceService {

    void credit(String accountId, long amount, String transactionId);

    void debit(String accountId, long amount, String transactionId);

    void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId);

    long getBalance(String accountId);
}
