package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Smoke test for the Spring Boot application context.
 *
 * <p>Uses a minimal property source to avoid requiring a real PostgreSQL
 * database during the test phase. The datasource is replaced with an
 * embedded H2 in-memory database for context loading only.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This is the primary smoke test for the application bootstrap.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, Spring will throw an exception
        // and this test will fail automatically.
    }

    /**
     * Verifies that the main() entry point can be invoked without throwing.
     * Uses an empty args array to simulate a no-argument startup.
     */
    @Test
    void main_doesNotThrow() {
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{}));
    }
}
