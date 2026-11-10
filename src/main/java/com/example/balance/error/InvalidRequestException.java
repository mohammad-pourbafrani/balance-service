package com.example.balance.error;

public class InvalidRequestException extends BalanceException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
