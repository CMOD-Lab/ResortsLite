package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context loads successfully
        // This is the primary smoke test for the application
    }

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Assert
        assertTrue(ResortsLiteApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class),
                "ResortsLiteApplication should be annotated with @SpringBootApplication");
    }

    @Test
    void applicationClass_hasMainMethod() throws NoSuchMethodException {
        // Assert
        assertNotNull(ResortsLiteApplication.class.getMethod("main", String[].class),
                "ResortsLiteApplication should have a main method");
    }
}
