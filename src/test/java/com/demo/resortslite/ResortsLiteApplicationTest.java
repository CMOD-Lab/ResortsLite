package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration smoke test for ResortsLiteApplication.
 * Uses an H2 in-memory datasource so no real PostgreSQL is required.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors
    }

    @Test
    void main_doesNotThrowException() {
        // Arrange / Act / Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none",
                        "--spring.datasource.url=jdbc:h2:mem:maintest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.sql.init.mode=never"})
        );
    }
}
