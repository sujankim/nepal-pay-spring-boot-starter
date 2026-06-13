package com.example.demo.controller;

import io.nepalpay.esewa.EsewaClient;
import io.nepalpay.khalti.KhaltiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check endpoint — confirms NepalPay beans are auto-configured.
 *
 * <p>Visit: GET http://localhost:8080/api/demo/health
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class HealthController {

    private final KhaltiClient khaltiClient;
    private final EsewaClient  esewaClient;

    /**
     * Returns the status of all configured NepalPay clients.
     *
     * @return map of gateway name to mode (SANDBOX or PRODUCTION)
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",          "UP",
                "khalti_mode",     khaltiClient.isSandbox() ? "SANDBOX" : "PRODUCTION",
                "khalti_base_url", khaltiClient.baseUrl(),
                "esewa_mode",      esewaClient.isSandbox()  ? "SANDBOX" : "PRODUCTION",
                "esewa_form_url",  esewaClient.formActionUrl(),
                "message",         "NepalPay beans are auto-configured and ready"
        ));
    }
}