package org.vivek.matchingengine.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.matchingengine.config.KafkaProducerConfig;
import org.vivek.matchingengine.orderbook.OrderBook;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class MatchingController {

    private final OrderBook orderBook;
    private final KafkaTemplate<String, TradeExecution> tradeKafkaTemplate;

    @PostMapping("/match")
    public ResponseEntity<Map<String, Object>> match(@RequestBody Order order) {
        log.info("Received order to match: {} symbol={} side={} qty={} price={}",
                order.getOrderId(), order.getSymbol(), order.getSide(),
                order.getQuantity(), order.getPrice());

        Optional<TradeExecution> execution = orderBook.match(order);

        Map<String, Object> response = new HashMap<>();

        if (execution.isPresent()) {
            TradeExecution trade = execution.get();

            // Publish to Kafka using orderId as key for ordering guarantees
            tradeKafkaTemplate.send(
                    KafkaProducerConfig.TOPIC_TRADE_EXECUTED,
                    order.getOrderId(),
                    trade
            ).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish trade {} to Kafka: {}", trade.getTradeId(), ex.getMessage());
                } else {
                    log.info("Trade {} published to Kafka partition {} offset {}",
                            trade.getTradeId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

            response.put("matched", true);
            response.put("tradeId", trade.getTradeId());
            log.info("Trade matched: {} @ price={}", trade.getTradeId(), trade.getExecutedPrice());
        } else {
            response.put("matched", false);
            response.put("tradeId", null);
            log.info("No match found for order {}; added to order book", order.getOrderId());
        }

        return ResponseEntity.ok(response);
    }
}
