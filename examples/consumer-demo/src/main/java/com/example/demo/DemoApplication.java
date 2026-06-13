package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NepalPay Consumer Demo Application.
 *
 * <p>This is a minimal Spring Boot application showing how to use the
 * NepalPay Spring Boot Starter to integrate Khalti and eSewa payments.
 *
 * <p>Run with: mvn spring-boot:run
 * Then visit: http://localhost:8080/api/demo/health
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}