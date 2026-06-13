package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NepalPay Consumer Demo Application.
 *
 * <p>This is a minimal Spring Boot 4 app showing how to integrate
 * NepalPay Spring Boot Starter for Khalti, eSewa, and ConnectIPS.
 *
 * <p>Run with: {@code mvn spring-boot:run}
 *
 * <p>Then test with:
 * <pre>
 * POST http://localhost:8080/api/demo/khalti/initiate
 * POST http://localhost:8080/api/demo/esewa/initiate
 * GET  http://localhost:8080/api/demo/health
 * </pre>
 *
 * <p>See README.md in this directory for full usage guide.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}