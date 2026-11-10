package com.example.balance.error;

import com.example.balance.transaction.RejectionReason;

public class InsufficientFundsException extends TransactionRejectedException {

    public InsufficientFundsException(String transactionId, String accountId) {
        super(transactionId, RejectionReason.INSUFFICIENT_FUNDS,
                "Insufficient funds in account " + accountId + " for transaction " + transactionId);
    }
}
