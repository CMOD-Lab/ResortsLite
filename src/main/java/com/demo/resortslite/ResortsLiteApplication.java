package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the ResortsLite Spring Boot application.
 *
 * <p>Upgraded to Java 17 / Spring Boot 3.2.x. The {@code @SpringBootApplication}
 * annotation enables auto-configuration, component scanning, and configuration
 * properties support. No additional module-system flags are required for this
 * application because Spring Boot 3.x and its dependencies are fully modular
 * and compatible with the Java 9+ module system out of the box.
 *
 * <p>Java 9+ Module System note: All reflection access used internally by Spring
 * and Hibernate is handled via {@code module-info} declarations in their respective
 * JARs. No {@code --add-opens} JVM arguments are needed for standard usage.
 */
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
