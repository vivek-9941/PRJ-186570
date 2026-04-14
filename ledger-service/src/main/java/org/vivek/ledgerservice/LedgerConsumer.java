package org.vivek.ledgerservice;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.TradeExecution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class LedgerConsumer {

    private final Map<String, Double> userBalances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> userHoldings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<LedgerEntry>> userLedgerHistory = new ConcurrentHashMap<>();
    private final Map<String, Double> reservedMarginByOrder = new ConcurrentHashMap<>();
    private final Set<String> processedTradeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> processedCancelledOrders = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @PostConstruct
    public void initHoldings() {
        userHoldings.put("U1", new ConcurrentHashMap<>(Map.of(
                "INFY", 8000.0d,
                "TCS", 500.0d,
                "RELIANCE", 200.0d
        )));
        userHoldings.put("U2", new ConcurrentHashMap<>(Map.of(
                "INFY", 0.0d,
                "TCS", 1000.0d
        )));
        userHoldings.put("U3", new ConcurrentHashMap<>());
    }

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "ledger-group")
    public void consumeTradeExecution(TradeExecution trade) {
            if (trade.getTradeId() != null && processedTradeIds.contains(trade.getTradeId())) {
            log.warn("Trade {} already processed, skipping duplicate event", trade.getTradeId());
            return;
        }

        double amount = trade.getExecutedPrice() * trade.getQuantity();

        // Deduct price * quantity from buyer's balance
        if (trade.getBuyerId() != null) {
            double buyerBalance = userBalances.merge(trade.getBuyerId(), -amount, Double::sum);
            userHoldings.computeIfAbsent(trade.getBuyerId(), key -> new ConcurrentHashMap<>())
                    .merge(trade.getSymbol(), trade.getQuantity(), Double::sum);
            recordEntry(trade, trade.getBuyerId(), "BUY", amount, buyerBalance);
        }

        // Credit price * quantity to seller's balance
        if (trade.getSellerId() != null) {
            double sellerBalance = userBalances.merge(trade.getSellerId(), amount, Double::sum);
            userHoldings.computeIfAbsent(trade.getSellerId(), key -> new ConcurrentHashMap<>())
                    .merge(trade.getSymbol(), -trade.getQuantity(), Double::sum);
            recordEntry(trade, trade.getSellerId(), "SELL", amount, sellerBalance);
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

    public Map<String, Double> getHoldings(String userId) {
        if (userId == null) {
            return Map.of();
        }
        return Map.copyOf(userHoldings.getOrDefault(userId, new ConcurrentHashMap<>()));
    }

    public List<LedgerEntry> getHistory(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<LedgerEntry> entries = new ArrayList<>(userLedgerHistory.getOrDefault(userId, List.of()));
        entries.sort(Comparator.comparing(LedgerEntry::getTimestamp).reversed());
        return entries;
    }

    void reserveMargin(String orderId, double amount) {
        reservedMarginByOrder.put(orderId, amount);
    }

    private void recordEntry(TradeExecution trade, String userId, String side, double amount, double balanceAfter) {
        LedgerEntry entry = LedgerEntry.builder()
                .entryId("LEDGER-" + (trade.getTradeId() == null ? "UNKNOWN" : trade.getTradeId()) + "-" + side + "-" + Instant.now().toEpochMilli())
                .tradeId(trade.getTradeId())
                .userId(userId)
                .side(side)
                .symbol(trade.getSymbol())
                .quantity(trade.getQuantity())
                .price(trade.getExecutedPrice())
                .amount(amount)
                .balanceAfter(balanceAfter)
                .timestamp(trade.getExecutedAt() != null ? trade.getExecutedAt() : Instant.now())
                .build();

        userLedgerHistory.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(entry);
    }
}
