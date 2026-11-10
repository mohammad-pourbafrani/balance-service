package com.example.balance.error;

public class AccountAlreadyExistsException extends BalanceException {

    public AccountAlreadyExistsException(String accountId) {
        super("Account " + accountId + " already exists");
    }
}
