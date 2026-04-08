package org.vivek.ledgerservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.TradeExecution;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LedgerConsumer {

    private final Map<String, Double> userBalances = new ConcurrentHashMap<>();
    private final Map<String, Double> reservedMarginByOrder = new ConcurrentHashMap<>();
    private final Set<String> processedTradeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> processedCancelledOrders = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "ledger-group")
    public void consumeTradeExecution(TradeExecution trade) {
            if (trade.getTradeId() != null && processedTradeIds.contains(trade.getTradeId())) {
            log.warn("Trade {} already processed, skipping duplicate event", trade.getTradeId());
            return;
        }

        double amount = trade.getExecutedPrice() * trade.getQuantity();

        // Deduct price * quantity from buyer's balance
        if (trade.getBuyerId() != null) {
            userBalances.merge(trade.getBuyerId(), -amount, Double::sum);
        }

        // Credit price * quantity to seller's balance
        if (trade.getSellerId() != null) {
            userBalances.merge(trade.getSellerId(), amount, Double::sum);
        }

        log.info("Ledger updated for trade {}", trade.getTradeId());
        if (trade.getTradeId() != null) {
            processedTradeIds.add(trade.getTradeId());
        }
    }

    @KafkaListener(topics = "${kafka.consumer.cancellation-topic}", groupId = "ledger-group")
    public void consumeCancellation(CancellationEvent cancellationEvent) {
        if (!processedCancelledOrders.add(cancellationEvent.getOrderId())) {
            log.info("Cancellation for order {} already processed, skipping", cancellationEvent.getOrderId());
            return;
        }

        Double releasedMarginValue = reservedMarginByOrder.remove(cancellationEvent.getOrderId());
        double releasedMargin = releasedMarginValue != null ? releasedMarginValue : 0.0d;
        if (cancellationEvent.getUserId() != null) {
            userBalances.merge(cancellationEvent.getUserId(), releasedMargin, Double::sum);
        }

        log.info("Margin released for cancelled order {}", cancellationEvent.getOrderId());
    }

    public Map<String, Object> getBalance(String userId) {
        if (userId == null) {
            return Map.of("userId", "unknown", "balance", 0.0);
        }
        return Map.of(
                "userId", userId,
                "balance", userBalances.getOrDefault(userId, 0.0)
        );
    }

    void reserveMargin(String orderId, double amount) {
        reservedMarginByOrder.put(orderId, amount);
    }
}
