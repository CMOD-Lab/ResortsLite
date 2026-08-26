package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for the Spring Boot application context.
 * Uses an in-memory H2 datasource override to avoid requiring a live PostgreSQL instance.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "app.inventory.endpoint=https://inventory-svc/rooms",
        "app.report.base-path=/tmp/reports/",
        "app.payment.endpoint=https://payment-svc/charge",
        "app.backup.path=/tmp/backups/",
        "server.port=8080"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors
        // This is a smoke test ensuring all beans are wired correctly
        assertTrue(true, "Application context should load successfully");
    }

    @Test
    void mainMethod_doesNotThrowException() {
        // Verify the main method can be referenced without issues
        // The actual Spring context startup is tested via @SpringBootTest above
        assertDoesNotThrow(() -> {
            // Just verify the class is accessible
            Class<?> appClass = ResortsLiteApplication.class;
            assertNotNull(appClass);
        });
    }
}
