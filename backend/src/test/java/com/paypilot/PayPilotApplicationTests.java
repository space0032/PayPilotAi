package com.paypilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boot smoke test: proves the full context starts against a real PostgreSQL
 * (Flyway migrations run, JPA validates) without any local infrastructure.
 *
 * Auto-skipped when no Docker daemon is available so unit-only runs still pass.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PayPilotApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoadsAgainstRealPostgres() {
    }
}
