package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link ResortsLiteApplication}.
 *
 * <p>A full Spring context load is intentionally avoided here to keep the
 * unit-test suite fast and free of external infrastructure (database, etc.).
 * Integration / context-load tests belong in a separate IT module.
 */
class ResortsLiteApplicationTest {

    // -----------------------------------------------------------------------
    // Class-level sanity checks
    // -----------------------------------------------------------------------

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Verify the @SpringBootApplication annotation is present
        assertTrue(
                ResortsLiteApplication.class.isAnnotationPresent(
                        org.springframework.boot.autoconfigure.SpringBootApplication.class),
                "ResortsLiteApplication must be annotated with @SpringBootApplication");
    }

    @Test
    void applicationClass_canBeInstantiated() {
        // The class must have a public no-arg constructor (default)
        ResortsLiteApplication app = new ResortsLiteApplication();
        assertNotNull(app, "ResortsLiteApplication must be instantiable");
    }

    @Test
    void applicationClass_mainMethodExists() throws NoSuchMethodException {
        // Verify the main(String[]) method is present and public
        java.lang.reflect.Method main =
                ResortsLiteApplication.class.getMethod("main", String[].class);
        assertNotNull(main, "main(String[]) method must exist");
        assertTrue(java.lang.reflect.Modifier.isPublic(main.getModifiers()),
                "main method must be public");
        assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()),
                "main method must be static");
    }

    @Test
    void applicationClass_isInCorrectPackage() {
        assertEquals("com.demo.resortslite",
                ResortsLiteApplication.class.getPackageName(),
                "Application class must reside in com.demo.resortslite package");
    }
}
