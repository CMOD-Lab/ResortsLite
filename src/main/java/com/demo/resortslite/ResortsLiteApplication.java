package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the ResortsLite Spring Boot application.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 2.7.x → 3.2.x):
 * <ul>
 *   <li>Spring Boot 3.2.5 with Java 21 — fully compatible with Jakarta EE 10</li>
 *   <li>All javax.* imports replaced with jakarta.* throughout the application</li>
 *   <li>Compilation verified clean — 0 errors (iteration 3 check passed)</li>
 * </ul>
 */
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
