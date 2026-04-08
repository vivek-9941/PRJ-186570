package org.vivek.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.vivek.commonmodule.model.Order;

import java.util.Map;

@Component
@Slf4j
public class MatchingEngineClient {

    private final RestTemplate restTemplate;
    private final String matchUrl;
    private final String cancelUrl;

    public MatchingEngineClient(@Value("${matching-engine.url:http://localhost:8081}") String matchingEngineBaseUrl) {
        this.restTemplate = new RestTemplate();
        String baseUrl = matchingEngineBaseUrl.endsWith("/")
                ? matchingEngineBaseUrl.substring(0, matchingEngineBaseUrl.length() - 1)
                : matchingEngineBaseUrl;
        this.matchUrl = baseUrl + "/api/v1/match";
        this.cancelUrl = baseUrl + "/api/v1/orders";
    }

    public MatchingEngineResponse route(Order order) {
        try {
            log.info("Routing order {} to matching engine at {}", order.getOrderId(), matchUrl);
            ResponseEntity<MatchingEngineResponse> response =
                    restTemplate.postForEntity(matchUrl, order, MatchingEngineResponse.class);
            return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
        } catch (RestClientException e) {
            log.error("Failed to connect to matching engine for order {}: {}", order.getOrderId(), e.getMessage());
            return null;
        }
    }

    public boolean cancel(String orderId) {
        try {
            log.info("Requesting cancellation for order {} via {}", orderId, cancelUrl);
            ResponseEntity<Map> response = restTemplate.exchange(
                    cancelUrl + "/" + orderId,
                    HttpMethod.DELETE,
                    null,
                    Map.class
            );
            Object cancelled = response.getBody() != null ? response.getBody().get("cancelled") : null;
            return Boolean.TRUE.equals(cancelled);
        } catch (RestClientException e) {
            log.error("Failed to cancel order {} in matching engine: {}", orderId, e.getMessage());
            return false;
        }
    }
}
