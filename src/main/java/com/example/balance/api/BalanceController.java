package com.example.balance.api;

import com.example.balance.account.Account;
import com.example.balance.account.AccountService;
import com.example.balance.service.BalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Thin HTTP adapter over {@link BalanceService}. All validation and business rules live in the service,
 * so the HTTP API and direct callers behave identically.
 */
@RestController
@RequestMapping("/api/v1")
public class BalanceController {

    private final BalanceService balanceService;
    private final AccountService accountService;

    public BalanceController(BalanceService balanceService, AccountService accountService) {
        this.balanceService = balanceService;
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<BalanceResponse> openAccount(@RequestBody OpenAccountRequest request) {
        Account account = accountService.open(request.accountId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{accountId}")
                .buildAndExpand(account.getId())
                .toUri();
        return ResponseEntity.created(location).body(new BalanceResponse(account.getId(), account.getBalance()));
    }

    @GetMapping("/accounts/{accountId}")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, balanceService.getBalance(accountId));
    }

    @PostMapping("/accounts/{accountId}/credits")
    public TransactionResponse credit(@PathVariable String accountId, @RequestBody AmountRequest request) {
        balanceService.credit(accountId, request.amount(), request.transactionId());
        return TransactionResponse.completed(request.transactionId());
    }

    @PostMapping("/accounts/{accountId}/debits")
    public TransactionResponse debit(@PathVariable String accountId, @RequestBody AmountRequest request) {
        balanceService.debit(accountId, request.amount(), request.transactionId());
        return TransactionResponse.completed(request.transactionId());
    }

    @PostMapping("/transfers")
    public TransactionResponse transfer(@RequestBody TransferRequest request) {
        balanceService.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.transactionId());
        return TransactionResponse.completed(request.transactionId());
    }

    public record OpenAccountRequest(String accountId) {
    }

    public record AmountRequest(long amount, String transactionId) {
    }

    public record TransferRequest(String sourceAccountId, String destinationAccountId, long amount,
                                  String transactionId) {
    }

    public record BalanceResponse(String accountId, long balance) {
    }

    public record TransactionResponse(String transactionId, String status) {

        static TransactionResponse completed(String transactionId) {
            return new TransactionResponse(transactionId, "COMPLETED");
        }
    }
}
