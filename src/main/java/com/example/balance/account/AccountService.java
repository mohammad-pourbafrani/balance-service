package com.example.balance.account;

import com.example.balance.error.AccountAlreadyExistsException;
import com.example.balance.service.Validation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Account lifecycle. Kept apart from {@link com.example.balance.service.BalanceService} on purpose:
 * opening an account is an administrative action, not a money movement.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Opens a new account with a zero balance. Money only ever enters an account through a credit,
     * so every unit of balance can be traced back to a transaction.
     */
    public Account open(String accountId) {
        Validation.requireId(accountId, "accountId");
        try {
            return accounts.saveAndFlush(Account.open(accountId));
        } catch (DataIntegrityViolationException e) {
            throw new AccountAlreadyExistsException(accountId);
        }
    }
}
