package com.example.balance.transaction;

public enum TransactionStatus {
    /** The transaction was applied to the balances. */
    COMPLETED,
    /**
     * The transaction was valid but declined by a business rule (e.g. insufficient funds).
     * Balances were left untouched. The outcome is final: retries with the same id get the same answer.
     */
    REJECTED
}
