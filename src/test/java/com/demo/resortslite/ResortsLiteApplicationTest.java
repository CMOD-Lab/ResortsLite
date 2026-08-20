package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResortsLiteApplication}.
 * Verifies the application entry-point class can be instantiated
 * without requiring a full Spring context.
 */
class ResortsLiteApplicationTest {

    @Test
    void applicationClass_canBeInstantiated() {
        // Arrange & Act
        ResortsLiteApplication app = new ResortsLiteApplication();

        // Assert
        assertNotNull(app, "ResortsLiteApplication should be instantiable");
    }

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Assert
        assertTrue(
                ResortsLiteApplication.class.isAnnotationPresent(
                        org.springframework.boot.autoconfigure.SpringBootApplication.class),
                "ResortsLiteApplication must be annotated with @SpringBootApplication");
    }

    @Test
    void applicationClass_hasMainMethod() throws NoSuchMethodException {
        // Assert — verify main(String[]) exists and is public static
        java.lang.reflect.Method main =
                ResortsLiteApplication.class.getMethod("main", String[].class);
        assertNotNull(main);
        assertTrue(java.lang.reflect.Modifier.isPublic(main.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()));
    }
}
