package com.example.balance.error;

/**
 * Base type for expected, client-facing failures. Anything else that escapes the service is a bug or an
 * infrastructure problem and is treated as such.
 */
public abstract class BalanceException extends RuntimeException {

    protected BalanceException(String message) {
        super(message);
    }
}
