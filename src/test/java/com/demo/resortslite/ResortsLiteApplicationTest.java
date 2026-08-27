package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for the Spring Boot application context.
 *
 * <p>Uses an in-memory H2 datasource (test scope) so no real PostgreSQL
 * instance is required during unit testing.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "app.inventory.endpoint=http://localhost:8081/rooms",
        "app.report.base-path=/tmp/test-reports/",
        "app.payment.endpoint=http://localhost:9090/charge",
        "app.report.download-base-url=https://test.reports.com/download"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors.
        // This is the primary smoke test for the application.
    }

    @Test
    void mainMethod_doesNotThrowException() {
        // Arrange / Act / Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none",
                        "--spring.datasource.url=jdbc:h2:mem:maintest;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--app.payment.endpoint=http://localhost:9090/charge",
                        "--app.report.base-path=/tmp/test-reports/",
                        "--app.report.download-base-url=https://test.reports.com/download",
                        "--spring.main.allow-bean-definition-overriding=true"})
        );
    }
}
