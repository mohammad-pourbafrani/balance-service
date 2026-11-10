package com.example.balance.error;

import com.example.balance.transaction.RejectionReason;

/**
 * A well-formed transaction that was declined by a business rule. The rejection is recorded, so retrying
 * with the same transactionId returns the same rejection.
 */
public abstract class TransactionRejectedException extends BalanceException {

    private final String transactionId;
    private final RejectionReason reason;

    protected TransactionRejectedException(String transactionId, RejectionReason reason, String message) {
        super(message);
        this.transactionId = transactionId;
        this.reason = reason;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
