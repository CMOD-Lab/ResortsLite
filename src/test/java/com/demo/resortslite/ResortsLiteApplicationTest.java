package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level tests for the ResortsLiteApplication main class.
 *
 * <p>These tests verify the application entry point without loading the full
 * Spring context, avoiding the need for an external database.
 */
class ResortsLiteApplicationTest {

    /**
     * Verifies that the ResortsLiteApplication class can be instantiated.
     */
    @Test
    void applicationClass_canBeInstantiated() {
        ResortsLiteApplication app = new ResortsLiteApplication();
        assertNotNull(app, "ResortsLiteApplication instance should not be null");
    }

    /**
     * Verifies that the application class is annotated with @SpringBootApplication
     * by checking the annotation is present on the class.
     */
    @Test
    void applicationClass_hasSpringBootApplicationAnnotation() {
        boolean hasAnnotation = ResortsLiteApplication.class
                .isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class);
        assertTrue(hasAnnotation, "ResortsLiteApplication should be annotated with @SpringBootApplication");
    }
}
