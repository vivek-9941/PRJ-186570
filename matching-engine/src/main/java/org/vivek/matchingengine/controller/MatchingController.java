package org.vivek.matchingengine.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.matchingengine.config.KafkaProducerConfig;
import org.vivek.matchingengine.orderbook.OrderBook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class MatchingController {

    private final OrderBook orderBook;
    private final KafkaTemplate<String, TradeExecution> tradeKafkaTemplate;
    private final KafkaTemplate<String, CancellationEvent> cancellationKafkaTemplate;

    @PostMapping("/match")
    public ResponseEntity<Map<String, Object>> match(@RequestBody Order order) {
        log.info("Received order to match: {} symbol={} side={} qty={} price={}",
                order.getOrderId(), order.getSymbol(), order.getSide(),
                order.getQuantity(), order.getPrice());

        double originalQty = order.getQuantity();
        List<TradeExecution> executions = orderBook.match(order);
        double totalFilled = executions.stream()
                .mapToDouble(TradeExecution::getQuantity)
                .sum();
        double remainingQty = Math.max(0.0d, originalQty - totalFilled);

        Map<String, Object> response = new HashMap<>();
        response.put("matched", !executions.isEmpty());
        response.put("fillCount", executions.size());
        response.put("totalFilled", totalFilled);
        response.put("remainingQty", remainingQty);
        response.put("executions", new ArrayList<>(executions));

        for (TradeExecution trade : executions) {
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
            log.info("Trade matched: {} @ price={} qty={}",
                    trade.getTradeId(), trade.getExecutedPrice(), trade.getQuantity());
        }

        if (executions.isEmpty()) {
            log.info("No match found for order {}; added to order book", order.getOrderId());
        } else {
            log.info("Order {} generated {} fills, totalFilled={}, remainingQty={}",
                    order.getOrderId(), executions.size(), totalFilled, remainingQty);
        }
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String orderId) {
        Order cancelledOrder = orderBook.cancelOrder(orderId);
        boolean cancelled = cancelledOrder != null;

        if (cancelledOrder != null) {
            CancellationEvent event = CancellationEvent.builder()
                    .orderId(cancelledOrder.getOrderId())
                    .userId(cancelledOrder.getUserId())
                    .symbol(cancelledOrder.getSymbol())
                    .cancelledAt(Instant.now())
                    .build();

            cancellationKafkaTemplate.send(
                    KafkaProducerConfig.TOPIC_ORDER_CANCELLED,
                    cancelledOrder.getOrderId(),
                    event
            ).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish cancellation for order {}: {}", orderId, ex.getMessage());
                } else {
                    log.info("Cancellation for order {} published to Kafka partition {} offset {}",
                            orderId,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        }

        return ResponseEntity.ok(Map.of("cancelled", cancelled));
    }
}
