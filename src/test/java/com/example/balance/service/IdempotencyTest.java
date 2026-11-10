package com.example.balance.service;

import com.example.balance.error.IdempotencyConflictException;
import com.example.balance.error.InsufficientFundsException;
import com.example.balance.support.BalanceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sequential retries. Concurrent duplicates are covered in {@link AbstractConcurrencyTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyTest extends BalanceTestSupport {

    @Test
    void repeatedCreditIsAppliedOnce() {
        String account = newAccount(1_000);

        balanceService.credit(account, 100, "TX-1-" + account);
        balanceService.credit(account, 100, "TX-1-" + account);
        balanceService.credit(account, 100, "TX-1-" + account);

        assertThat(balanceOf(account)).isEqualTo(1_100);
    }

    @Test
    void repeatedDebitIsAppliedOnce() {
        String account = newAccount(1_000);
        String tx = newTransactionId();

        balanceService.debit(account, 300, tx);
        balanceService.debit(account, 300, tx);
        balanceService.debit(account, 300, tx);

        assertThat(balanceOf(account)).isEqualTo(700);
    }

    @Test
    void repeatedTransferIsAppliedOnce() {
        String a = newAccount(1_000);
        String b = newAccount(500);
        String tx = newTransactionId();

        balanceService.transfer(a, b, 300, tx);
        balanceService.transfer(a, b, 300, tx);
        balanceService.transfer(a, b, 300, tx);

        assertThat(balanceOf(a)).isEqualTo(700);
        assertThat(balanceOf(b)).isEqualTo(800);
    }

    @Test
    void retryOfRejectedDebitStaysRejectedEvenAfterTopUp() {
        String account = newAccount(100);
        String tx = newTransactionId();

        assertThatThrownBy(() -> balanceService.debit(account, 500, tx))
                .isInstanceOf(InsufficientFundsException.class);

        balanceService.credit(account, 1_000, newTransactionId());

        // The first answer was final. A retry must not turn a declined payment into an accepted one
        // at some arbitrary later point; the client has to submit a new transaction for that.
        assertThatThrownBy(() -> balanceService.debit(account, 500, tx))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balanceOf(account)).isEqualTo(1_100);
    }

    @Test
    void reusingTransactionIdWithDifferentAmountIsAConflict() {
        String account = newAccount(1_000);
        String tx = newTransactionId();
        balanceService.credit(account, 100, tx);

        assertThatThrownBy(() -> balanceService.credit(account, 200, tx))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(account)).isEqualTo(1_100);
    }

    @Test
    void reusingTransactionIdForDifferentOperationTypeIsAConflict() {
        String account = newAccount(1_000);
        String tx = newTransactionId();
        balanceService.credit(account, 100, tx);

        assertThatThrownBy(() -> balanceService.debit(account, 100, tx))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(account)).isEqualTo(1_100);
    }

    @Test
    void reusingTransactionIdOnAnotherAccountIsAConflict() {
        String a = newAccount(1_000);
        String b = newAccount(1_000);
        String tx = newTransactionId();
        balanceService.credit(a, 100, tx);

        assertThatThrownBy(() -> balanceService.credit(b, 100, tx))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(a)).isEqualTo(1_100);
        assertThat(balanceOf(b)).isEqualTo(1_000);
    }

    @Test
    void reversedTransferWithSameIdIsAConflict() {
        String a = newAccount(1_000);
        String b = newAccount(1_000);
        String tx = newTransactionId();
        balanceService.transfer(a, b, 100, tx);

        assertThatThrownBy(() -> balanceService.transfer(b, a, 100, tx))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(a)).isEqualTo(900);
        assertThat(balanceOf(b)).isEqualTo(1_100);
    }
}
