package com.example.balance.service;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Same scenarios against the database we actually deploy on. Skipped automatically when Docker isn't
 * available, so {@code ./mvnw test} still works on a bare machine.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyTest extends AbstractConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("balance").withUsername("balance").withUsername("balance");
}
