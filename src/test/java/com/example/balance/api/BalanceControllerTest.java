package com.example.balance.api;

import com.example.balance.support.BalanceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract: status codes, problem codes and request mapping. Business behaviour is covered at the
 * service level, so this only checks that it's exposed correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BalanceControllerTest extends BalanceTestSupport {

    @Autowired
    private MockMvc mvc;

    @Test
    void opensAccount() throws Exception {
        String accountId = "ACC-" + UUID.randomUUID();

        postJson("/api/v1/accounts", """
                {"accountId": "%s"}
                """.formatted(accountId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/accounts/" + accountId))
                .andExpect(jsonPath("$.balance").value(0));

        postJson("/api/v1/accounts", """
                {"accountId": "%s"}
                """.formatted(accountId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void creditDebitAndReadBalance() throws Exception {
        String account = newAccount(1_000);

        postJson("/api/v1/accounts/" + account + "/credits", """
                {"amount": 500, "transactionId": "%s"}
                """.formatted(newTransactionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        postJson("/api/v1/accounts/" + account + "/debits", """
                {"amount": 200, "transactionId": "%s"}
                """.formatted(newTransactionId()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/accounts/" + account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account))
                .andExpect(jsonPath("$.balance").value(1_300));
    }

    @Test
    void transfer() throws Exception {
        String a = newAccount(1_000);
        String b = newAccount(500);

        postJson("/api/v1/transfers", """
                {"sourceAccountId": "%s", "destinationAccountId": "%s", "amount": 300, "transactionId": "%s"}
                """.formatted(a, b, newTransactionId()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/accounts/" + a)).andExpect(jsonPath("$.balance").value(700));
        mvc.perform(get("/api/v1/accounts/" + b)).andExpect(jsonPath("$.balance").value(800));
    }

    @Test
    void invalidAmountIsBadRequest() throws Exception {
        String account = newAccount(1_000);

        postJson("/api/v1/accounts/" + account + "/credits", """
                {"amount": 0, "transactionId": "%s"}
                """.formatted(newTransactionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingAmountIsBadRequest() throws Exception {
        String account = newAccount(1_000);

        postJson("/api/v1/accounts/" + account + "/credits", """
                {"transactionId": "%s"}
                """.formatted(newTransactionId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/accounts/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void insufficientFundsIsUnprocessable() throws Exception {
        String account = newAccount(100);
        String tx = newTransactionId();

        postJson("/api/v1/accounts/" + account + "/debits", """
                {"amount": 500, "transactionId": "%s"}
                """.formatted(tx))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transactionId").value(tx));
    }

    @Test
    void reusedTransactionIdIsConflict() throws Exception {
        String account = newAccount(1_000);
        String tx = newTransactionId();
        balanceService.credit(account, 100, tx);

        postJson("/api/v1/accounts/" + account + "/credits", """
                {"amount": 999, "transactionId": "%s"}
                """.formatted(tx))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ID_REUSED"));
    }

    @Test
    void retryReturnsTheSameSuccess() throws Exception {
        String account = newAccount(1_000);
        String body = """
                {"amount": 100, "transactionId": "%s"}
                """.formatted(newTransactionId());

        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/accounts/" + account + "/credits", body).andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/accounts/" + account)).andExpect(jsonPath("$.balance").value(1_100));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        postJson("/api/v1/transfers", "{not json").andExpect(status().isBadRequest());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
