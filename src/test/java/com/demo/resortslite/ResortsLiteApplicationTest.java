package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link ResortsLiteApplication}.
 * Verifies the Spring Boot application context loads correctly
 * and the main entry point is accessible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:application-test.properties")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context starts without errors.
     * This is the primary smoke test for the application bootstrap.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically.
        // No explicit assertions needed — the @SpringBootTest annotation
        // handles context loading verification.
    }

    /**
     * Verifies that the ResortsLiteApplication class exists and is annotated correctly.
     */
    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Assert
        assertNotNull(ResortsLiteApplication.class.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class));
    }

    /**
     * Verifies that the main method exists and is accessible.
     */
    @Test
    void applicationClass_hasMainMethod() throws NoSuchMethodException {
        // Assert — main method must exist with String[] parameter
        assertNotNull(ResortsLiteApplication.class.getMethod("main", String[].class));
    }
}
