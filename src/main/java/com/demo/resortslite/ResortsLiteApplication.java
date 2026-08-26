package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ResortsLite Spring Boot application.
 *
 * <p>Bootstraps the Spring application context and starts the embedded
 * Tomcat server. Compatible with Java 17 and Spring Boot 3.2.x.</p>
 */
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
