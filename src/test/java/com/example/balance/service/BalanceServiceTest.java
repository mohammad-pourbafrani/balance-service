package com.example.balance.service;

import com.example.balance.error.AccountAlreadyExistsException;
import com.example.balance.error.AccountNotFoundException;
import com.example.balance.error.BalanceLimitExceededException;
import com.example.balance.error.InsufficientFundsException;
import com.example.balance.error.InvalidRequestException;
import com.example.balance.support.BalanceTestSupport;
import com.example.balance.transaction.RejectionReason;
import com.example.balance.transaction.TransactionRecord;
import com.example.balance.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BalanceServiceTest extends BalanceTestSupport {

    @Test
    void creditIncreasesBalance() {
        String account = newAccount(1_000);

        balanceService.credit(account, 500, newTransactionId());

        assertThat(balanceOf(account)).isEqualTo(1_500);
    }

    @Test
    void creditIsRecorded() {
        String account = newAccount(0);
        String tx = newTransactionId();

        balanceService.credit(account, 500, tx);

        TransactionRecord record = transactionRecords.findById(tx).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(record.getDestinationAccountId()).isEqualTo(account);
        assertThat(record.getSourceAccountId()).isNull();
        assertThat(record.getAmount()).isEqualTo(500);
    }

    @Test
    void creditRejectsOverflowAndLeavesBalanceUntouched() {
        String account = newAccount(Long.MAX_VALUE - 10);

        assertThatThrownBy(() -> balanceService.credit(account, 11, newTransactionId()))
                .isInstanceOf(BalanceLimitExceededException.class);
        assertThat(balanceOf(account)).isEqualTo(Long.MAX_VALUE - 10);
    }

    @Test
    void debitDecreasesBalance() {
        String account = newAccount(1_000);

        balanceService.debit(account, 700, newTransactionId());

        assertThat(balanceOf(account)).isEqualTo(300);
    }

    @Test
    void debitCanEmptyTheAccount() {
        String account = newAccount(1_000);

        balanceService.debit(account, 1_000, newTransactionId());

        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void debitFailsOnInsufficientFundsAndLeavesBalanceUntouched() {
        String account = newAccount(1_000);
        String tx = newTransactionId();

        assertThatThrownBy(() -> balanceService.debit(account, 1_200, tx))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(account)).isEqualTo(1_000);
        TransactionRecord record = transactionRecords.findById(tx).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(record.getRejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        String a = newAccount(1_000);
        String b = newAccount(500);

        balanceService.transfer(a, b, 300, newTransactionId());

        assertThat(balanceOf(a)).isEqualTo(700);
        assertThat(balanceOf(b)).isEqualTo(800);
    }

    @Test
    void transferFailsOnInsufficientFundsAndTouchesNeitherAccount() {
        String a = newAccount(1_000);
        String b = newAccount(500);

        assertThatThrownBy(() -> balanceService.transfer(a, b, 1_001, newTransactionId()))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(a)).isEqualTo(1_000);
        assertThat(balanceOf(b)).isEqualTo(500);
    }

    @Test
    void transferDestinationOverflowRollsBackTheWholeTransfer() {
        String a = newAccount(1_000);
        String b = newAccount(Long.MAX_VALUE - 100);

        assertThatThrownBy(() -> balanceService.transfer(a, b, 101, newTransactionId()))
                .isInstanceOf(BalanceLimitExceededException.class);

        assertThat(balanceOf(a)).isEqualTo(1_000);
        assertThat(balanceOf(b)).isEqualTo(Long.MAX_VALUE - 100);
    }

    @Test
    void transferToTheSameAccountIsRejectedWithoutSideEffects() {
        String a = newAccount(1_000);
        String tx = newTransactionId();

        assertThatThrownBy(() -> balanceService.transfer(a, a, 100, tx))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("different");

        assertThat(balanceOf(a)).isEqualTo(1_000);
        assertThat(transactionRecords.existsById(tx)).isFalse();
    }

    @Test
    void transferToUnknownAccountFailsAndDoesNotDebitTheSource() {
        String a = newAccount(1_000);

        assertThatThrownBy(() -> balanceService.transfer(a, "NOPE", 100, newTransactionId()))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(balanceOf(a)).isEqualTo(1_000);
    }

    @Test
    void transferFromUnknownAccountFails() {
        String b = newAccount(1_000);

        assertThatThrownBy(() -> balanceService.transfer("NOPE", b, 100, newTransactionId()))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(balanceOf(b)).isEqualTo(1_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveAmounts(long amount) {
        String a = newAccount(1_000);
        String b = newAccount(1_000);

        assertThatThrownBy(() -> balanceService.credit(a, amount, newTransactionId()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> balanceService.debit(a, amount, newTransactionId()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> balanceService.transfer(a, b, amount, newTransactionId()))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(balanceOf(a)).isEqualTo(1_000);
        assertThat(balanceOf(b)).isEqualTo(1_000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlankTransactionId(String transactionId) {
        String a = newAccount(1_000);

        assertThatThrownBy(() -> balanceService.credit(a, 100, transactionId))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(balanceOf(a)).isEqualTo(1_000);
    }

    @Test
    void rejectsOverlongIds() {
        String tooLong = "x".repeat(65);

        assertThatThrownBy(() -> balanceService.credit(tooLong, 100, newTransactionId()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> balanceService.credit(newAccount(0), 100, tooLong))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void unknownAccountFailsForEveryOperation() {
        assertThatThrownBy(() -> balanceService.credit("NOPE", 100, newTransactionId()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> balanceService.debit("NOPE", 100, newTransactionId()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> balanceService.getBalance("NOPE"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void operationOnUnknownAccountIsNotRecorded() {
        String tx = newTransactionId();

        assertThatThrownBy(() -> balanceService.credit("NOPE", 100, tx))
                .isInstanceOf(AccountNotFoundException.class);

        // nothing was processed, so the id stays free and a corrected retry can still use it
        assertThat(transactionRecords.existsById(tx)).isFalse();
    }

    @Test
    void accountCannotBeOpenedTwice() {
        String a = newAccount(1_000);

        assertThatThrownBy(() -> accountService.open(a))
                .isInstanceOf(AccountAlreadyExistsException.class);
        assertThat(balanceOf(a)).isEqualTo(1_000);
    }
}
