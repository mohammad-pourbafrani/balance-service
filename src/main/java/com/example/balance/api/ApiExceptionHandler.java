package com.example.balance.api;

import com.example.balance.error.AccountAlreadyExistsException;
import com.example.balance.error.AccountNotFoundException;
import com.example.balance.error.IdempotencyConflictException;
import com.example.balance.error.InvalidRequestException;
import com.example.balance.error.TransactionRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain errors to RFC 9457 problem responses. Each response carries a stable {@code code}
 * property so clients don't have to parse messages.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalid(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    ProblemDetail handleAlreadyExists(AccountAlreadyExistsException e) {
        return problem(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "TRANSACTION_ID_REUSED", e.getMessage());
    }

    @ExceptionHandler(TransactionRejectedException.class)
    ProblemDetail handleRejected(TransactionRejectedException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getReason().name(), e.getMessage());
        problem.setProperty("transactionId", e.getTransactionId());
        return problem;
    }

    /**
     * Lock wait timeouts (and deadlocks, which the lock ordering should prevent) are transient. Nothing was
     * committed, and since every operation is idempotent the client can safely retry with the same id.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException e) {
        log.warn("Lock acquisition failed, asking client to retry", e);
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                "The account is busy, retry the request with the same transactionId");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
