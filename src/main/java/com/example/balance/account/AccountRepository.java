package com.example.balance.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Loads the account and takes a row-level write lock on it ({@code SELECT ... FOR UPDATE}).
     * The lock is held until the surrounding transaction ends. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") String id);

    @Query("select a.balance from Account a where a.id = :id")
    Optional<Long> findBalanceById(@Param("id") String id);
}
