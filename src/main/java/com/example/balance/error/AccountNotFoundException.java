package com.example.balance.error;

public class AccountNotFoundException extends BalanceException {

    private final String accountId;

    public AccountNotFoundException(String accountId) {
        super("Account " + accountId + " does not exist");
        this.accountId = accountId;
    }

    public String getAccountId() {
        return accountId;
    }
}
