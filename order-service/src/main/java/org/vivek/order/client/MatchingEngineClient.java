package org.vivek.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.vivek.commonmodule.model.Order;

@Component
@Slf4j
public class MatchingEngineClient {

    private final RestTemplate restTemplate;
    private final String matchUrl = "http://matching-engine:8081/api/v1/match";

    public MatchingEngineClient() {
        this.restTemplate = new RestTemplate();
    }

    public boolean route(Order order) {
        try {
            log.info("Routing order {} to matching engine at {}", order.getOrderId(), matchUrl);
            ResponseEntity<String> response = restTemplate.postForEntity(matchUrl, order, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.error("Failed to connect to matching engine for order {}: {}", order.getOrderId(), e.getMessage());
            return false;
        }
    }
}
