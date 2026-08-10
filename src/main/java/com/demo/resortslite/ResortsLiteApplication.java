package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * ResortsLite Spring Boot application entry point.
 *
 * <p>cr-java-0065 FIX: {@code @EnableRedisHttpSession} activates Spring Session
 * with Amazon ElastiCache for Redis as the session store, replacing the default
 * in-memory {@code HttpSession}.  This makes all application instances stateless
 * and enables safe horizontal scaling behind an AWS Application Load Balancer.</p>
 */
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
