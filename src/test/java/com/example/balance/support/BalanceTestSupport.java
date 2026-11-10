package com.example.balance.support;

import com.example.balance.account.AccountService;
import com.example.balance.service.BalanceService;
import com.example.balance.transaction.TransactionRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Shared helpers. Tests never clean the database; instead every test works on freshly created accounts
 * with random ids, so tests stay independent even when they share a Spring context and a database.
 */
public abstract class BalanceTestSupport {

    @Autowired
    protected BalanceService balanceService;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected TransactionRecordRepository transactionRecords;

    /** Opens an account and funds it through a regular credit, the same way production does. */
    protected String newAccount(long initialBalance) {
        String accountId = "ACC-" + UUID.randomUUID();
        accountService.open(accountId);
        if (initialBalance > 0) {
            balanceService.credit(accountId, initialBalance, newTransactionId());
        }
        return accountId;
    }

    protected static String newTransactionId() {
        return "TX-" + UUID.randomUUID();
    }

    protected long balanceOf(String accountId) {
        return balanceService.getBalance(accountId);
    }
}
