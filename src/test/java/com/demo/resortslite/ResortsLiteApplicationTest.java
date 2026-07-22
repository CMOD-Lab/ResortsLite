package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResortsLiteApplicationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // ResortsLiteApplication tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void applicationClass_canBeInstantiated() {
        // Arrange / Act
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
                "ResortsLiteApplication should be annotated with @SpringBootApplication");
    }

    @Test
    void applicationClass_mainMethodExists() throws NoSuchMethodException {
        // Assert
        assertNotNull(
                ResortsLiteApplication.class.getMethod("main", String[].class),
                "main(String[]) method should exist");
    }

    @Test
    void applicationClass_mainMethodIsPublicAndStatic() throws NoSuchMethodException {
        // Arrange
        java.lang.reflect.Method mainMethod =
                ResortsLiteApplication.class.getMethod("main", String[].class);

        // Assert
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()),
                "main method should be public");
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()),
                "main method should be static");
    }

    @Test
    void applicationClass_mainMethodReturnsVoid() throws NoSuchMethodException {
        // Arrange
        java.lang.reflect.Method mainMethod =
                ResortsLiteApplication.class.getMethod("main", String[].class);

        // Assert
        assertEquals(void.class, mainMethod.getReturnType(),
                "main method should return void");
    }
}
