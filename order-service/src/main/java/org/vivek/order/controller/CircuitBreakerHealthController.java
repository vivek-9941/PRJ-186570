package org.vivek.order.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class CircuitBreakerHealthController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerHealthController(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/circuit-breakers")
    public Map<String, Map<String, Object>> getCircuitBreakerHealth() {
        return Map.of(
                "riskService", describe("riskService"),
                "marginService", describe("marginService"),
                "complianceService", describe("complianceService")
        );
    }

    private Map<String, Object> describe(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", circuitBreaker.getState().name());
        payload.put("failureRate", formatFailureRate(metrics.getFailureRate()));
        if (circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN) {
            payload.put("calls", metrics.getNumberOfBufferedCalls());
        }
        return payload;
    }

    private String formatFailureRate(float failureRate) {
        if (failureRate < 0) {
            return "0%";
        }
        return Math.round(failureRate) + "%";
    }
}
