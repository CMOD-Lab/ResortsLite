package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for {@link ResortsLiteApplication}.
 * Verifies that the Spring application context loads successfully.
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
     * This is the standard Spring Boot smoke test.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail with an exception.
        // No explicit assertion needed — the @SpringBootTest annotation handles it.
    }

    /**
     * Verifies that the main() entry point can be invoked without throwing.
     * Uses an empty args array to simulate a no-argument startup.
     */
    @Test
    void main_doesNotThrowException() {
        // Arrange / Act / Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{}),
                "main() should not throw any exception"
        );
    }

    /**
     * Verifies that the application class can be instantiated.
     */
    @Test
    void applicationClass_canBeInstantiated() {
        // Act
        ResortsLiteApplication app = new ResortsLiteApplication();

        // Assert
        assertNotNull(app);
    }
}
