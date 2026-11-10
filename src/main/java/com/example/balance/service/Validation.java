package com.example.balance.service;

import com.example.balance.error.InvalidRequestException;

public final class Validation {

    /** Matches the width of the id columns in the schema. */
    public static final int MAX_ID_LENGTH = 64;

    private Validation() {
    }

    public static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(name + " must not be blank");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new InvalidRequestException(name + " must be at most " + MAX_ID_LENGTH + " characters");
        }
        return value;
    }

    public static long requirePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidRequestException("amount must be positive, got " + amount);
        }
        return amount;
    }
}
