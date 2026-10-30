package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResortsLiteApplication.
 * Uses H2 in-memory database to avoid requiring a real MySQL instance.
 */
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
    void contextLoads_applicationContextStartsSuccessfully() {
        // If the Spring context loads without throwing, the test passes.
        // This verifies that all beans are correctly wired.
    }

    @Test
    void main_doesNotThrowException() {
        // Arrange & Act & Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--server.port=0"}),
                "main() should not throw any exception"
        );
    }
}
