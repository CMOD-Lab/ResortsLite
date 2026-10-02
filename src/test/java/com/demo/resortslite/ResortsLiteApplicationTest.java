package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration / smoke tests for ResortsLiteApplication.
 * Verifies that the Spring application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This is the standard Spring Boot smoke test.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically.
        // No explicit assertion needed — Spring Boot test infrastructure handles it.
    }

    /**
     * Verifies that the main method can be invoked without throwing an exception.
     * Uses an empty args array to avoid starting a full server.
     */
    @Test
    void mainMethod_doesNotThrowException() {
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none"}));
    }
}
