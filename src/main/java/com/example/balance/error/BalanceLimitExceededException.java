package com.example.balance.error;

import com.example.balance.transaction.RejectionReason;

public class BalanceLimitExceededException extends TransactionRejectedException {

    public BalanceLimitExceededException(String transactionId, String accountId) {
        super(transactionId, RejectionReason.BALANCE_LIMIT_EXCEEDED,
                "Transaction " + transactionId + " would exceed the maximum balance of account " + accountId);
    }
}
