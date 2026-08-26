package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for {@link ResortsLiteApplication}.
 *
 * <p>Verifies that the Spring application context loads successfully
 * and that the main entry point is accessible.</p>
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
        // No explicit assertion needed — the @SpringBootTest annotation handles it.
    }

    /**
     * Verifies that the main method can be invoked without throwing an exception.
     * Uses an empty args array to simulate a no-argument startup.
     */
    @Test
    void main_withEmptyArgs_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}),
                "main() should not throw an exception with empty args");
    }
}
