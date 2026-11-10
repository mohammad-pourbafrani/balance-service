package com.example.balance.service;

import com.example.balance.account.AccountRepository;
import com.example.balance.error.IdempotencyConflictException;
import com.example.balance.error.InsufficientFundsException;
import com.example.balance.support.BalanceTestSupport;
import com.example.balance.support.ConcurrentRunner;
import com.example.balance.support.ConcurrentRunner.Outcomes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Concurrency scenarios. Run against H2 by default and against a real Postgres when Docker is available,
 * since locking behaviour is ultimately a property of the database.
 * <p>
 * Every scenario checks an exact expected end state, not just "no exception": lost updates and double
 * applications only show up as wrong numbers.
 */
@SpringBootTest
abstract class AbstractConcurrencyTest extends BalanceTestSupport {

    private static final int THREADS = 16;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void onlyOneOfTwoConcurrentOverlappingDebitsSucceeds() throws Exception {
        // the race is short, so give it a few chances
        for (int round = 0; round < 20; round++) {
            String account = newAccount(1_000);

            Outcomes outcomes = ConcurrentRunner.run(2, List.of(
                    () -> balanceService.debit(account, 700, newTransactionId()),
                    () -> balanceService.debit(account, 700, newTransactionId())));

            assertThat(outcomes.successCount()).as("round %d", round).isEqualTo(1);
            assertThat(outcomes.failureCount(InsufficientFundsException.class)).as("round %d", round).isEqualTo(1);
            assertThat(balanceOf(account)).as("round %d", round).isEqualTo(300);
        }
    }

    @Test
    void concurrentDebitsNeverOverdrawTheAccount() throws Exception {
        String account = newAccount(50_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            tasks.add(() -> balanceService.debit(account, 100, newTransactionId()));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks);

        assertThat(outcomes.unexpectedFailures(InsufficientFundsException.class)).isEmpty();
        assertThat(outcomes.successCount()).isEqualTo(500);
        assertThat(outcomes.failureCount(InsufficientFundsException.class)).isEqualTo(500);
        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void concurrentCreditsAndDebitsOnOneAccountLoseNoUpdates() throws Exception {
        String account = newAccount(100_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            tasks.add(() -> balanceService.credit(account, 50, newTransactionId()));
            tasks.add(() -> balanceService.debit(account, 30, newTransactionId()));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks);

        assertThat(outcomes.unexpectedFailures()).isEmpty();
        // 100,000 + 500 * 50 - 500 * 30
        assertThat(balanceOf(account)).isEqualTo(110_000);
    }

    @Test
    void concurrentDuplicatesOfACreditAreAppliedOnce() throws Exception {
        String account = newAccount(1_000);
        String tx = newTransactionId();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> balanceService.credit(account, 500, tx));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks);

        // every duplicate sees the same successful outcome
        assertThat(outcomes.unexpectedFailures()).isEmpty();
        assertThat(balanceOf(account)).isEqualTo(1_500);
    }

    @Test
    void concurrentDuplicatesOfATransferAreAppliedOnce() throws Exception {
        String a = newAccount(1_000);
        String b = newAccount(500);
        String tx = newTransactionId();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> balanceService.transfer(a, b, 300, tx));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks);

        assertThat(outcomes.unexpectedFailures()).isEmpty();
        assertThat(balanceOf(a)).isEqualTo(700);
        assertThat(balanceOf(b)).isEqualTo(800);
    }

    @Test
    void transactionIdRacedAcrossDifferentAccountsIsAppliedOnlyOnce() throws Exception {
        // The two requests lock different rows, so they don't serialize on account locks.
        // Only the primary key on transaction_record stands between them and a double application.
        for (int round = 0; round < 20; round++) {
            String a = newAccount(1_000);
            String b = newAccount(1_000);
            String tx = newTransactionId();

            Outcomes outcomes = ConcurrentRunner.run(2, List.of(
                    () -> balanceService.credit(a, 100, tx),
                    () -> balanceService.credit(b, 100, tx)));

            assertThat(outcomes.unexpectedFailures(IdempotencyConflictException.class)).as("round %d", round).isEmpty();
            assertThat(outcomes.successCount()).as("round %d", round).isEqualTo(1);
            assertThat(balanceOf(a) + balanceOf(b)).as("round %d", round).isEqualTo(2_100);
        }
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        // The classic deadlock: A→B locks A then waits for B, B→A locks B then waits for A.
        // With ordered locking this has to finish, and well before any lock timeout.
        String a = newAccount(100_000);
        String b = newAccount(100_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            tasks.add(() -> balanceService.transfer(a, b, 10, newTransactionId()));
            tasks.add(() -> balanceService.transfer(b, a, 10, newTransactionId()));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks, 60, TimeUnit.SECONDS);

        assertThat(outcomes.unexpectedFailures()).isEmpty();
        assertThat(balanceOf(a)).isEqualTo(100_000);
        assertThat(balanceOf(b)).isEqualTo(100_000);
    }

    @Test
    void randomTransfersBetweenManyAccountsConserveMoney() throws Exception {
        int accountCount = 8;
        long initialBalance = 10_000;
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(newAccount(initialBalance));
        }

        // Amounts are large enough relative to the balances that a good share of transfers get declined,
        // which exercises the "check then act" path under contention.
        Random random = new Random(42);
        List<String[]> pairs = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            int from = random.nextInt(accountCount);
            int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;
            long amount = 1 + random.nextInt(3_000);
            String source = accounts.get(from);
            String destination = accounts.get(to);
            pairs.add(new String[]{source, destination});
            amounts.add(amount);
            tasks.add(() -> balanceService.transfer(source, destination, amount, newTransactionId()));
        }

        Outcomes outcomes = ConcurrentRunner.run(THREADS, tasks);

        assertThat(outcomes.unexpectedFailures(InsufficientFundsException.class)).isEmpty();

        // Successful transfers commute, so whatever order they ran in, the end state is fully determined
        // by which of them succeeded.
        Map<String, Long> expected = new HashMap<>();
        accounts.forEach(id -> expected.put(id, initialBalance));
        for (int i = 0; i < tasks.size(); i++) {
            if (outcomes.succeeded(i)) {
                expected.merge(pairs.get(i)[0], -amounts.get(i), Long::sum);
                expected.merge(pairs.get(i)[1], amounts.get(i), Long::sum);
            }
        }

        long total = 0;
        for (String id : accounts) {
            long balance = balanceOf(id);
            assertThat(balance).as("balance of %s", id).isEqualTo(expected.get(id)).isNotNegative();
            total += balance;
        }
        assertThat(total).isEqualTo(accountCount * initialBalance);
    }

    @Test
    void lockOnOneAccountDoesNotBlockAnotherAccount() throws Exception {
        String a = newAccount(1_000);
        String b = newAccount(1_000);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Simulates a long-running operation on A by holding its row lock.
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                accountRepository.findByIdForUpdate(a).orElseThrow();
                lockHeld.countDown();
                awaitQuietly(release);
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> creditA = executor.submit(() -> balanceService.credit(a, 10, newTransactionId()));

            // B shares no lock with the transaction holding A, so it goes straight through...
            assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> balanceService.credit(b, 10, newTransactionId()));
            assertThat(balanceOf(b)).isEqualTo(1_010);

            // ...while the credit on A has to wait. Reads are not blocked by the lock.
            assertThatThrownBy(() -> creditA.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(balanceOf(a)).isEqualTo(1_000);

            release.countDown();
            creditA.get(5, TimeUnit.SECONDS);
            holder.get(5, TimeUnit.SECONDS);
            assertThat(balanceOf(a)).isEqualTo(1_010);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
