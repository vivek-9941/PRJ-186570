package org.vivek.order.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerHealthControllerTest {

    @Test
    void returnsStateForAllConfiguredCircuitBreakers() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        CircuitBreaker risk = registry.circuitBreaker("riskService");
        CircuitBreaker margin = registry.circuitBreaker("marginService");
        CircuitBreaker compliance = registry.circuitBreaker("complianceService");

        risk.transitionToOpenState();
        compliance.transitionToOpenState();
        compliance.transitionToHalfOpenState();

        CircuitBreakerHealthController controller = new CircuitBreakerHealthController(registry);

        Map<String, Map<String, Object>> payload = controller.getCircuitBreakerHealth();

        assertEquals("OPEN", payload.get("riskService").get("state"));
        assertEquals("CLOSED", payload.get("marginService").get("state"));
        assertEquals("HALF_OPEN", payload.get("complianceService").get("state"));
        assertTrue(payload.get("complianceService").containsKey("calls"));
    }
}
