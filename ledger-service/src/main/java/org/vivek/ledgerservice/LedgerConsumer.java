package org.vivek.ledgerservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.ledgerservice.entity.LedgerEntryEntity;
import org.vivek.ledgerservice.entity.ProcessedEvent;
import org.vivek.ledgerservice.entity.UserBalance;
import org.vivek.ledgerservice.entity.UserHolding;
import org.vivek.ledgerservice.entity.UserHoldingId;
import org.vivek.ledgerservice.repository.LedgerEntryRepository;
import org.vivek.ledgerservice.repository.ProcessedEventRepository;
import org.vivek.ledgerservice.repository.UserBalanceRepository;
import org.vivek.ledgerservice.repository.UserHoldingRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LedgerConsumer {

    @Autowired
    private UserBalanceRepository userBalanceRepository;

    @Autowired
    private UserHoldingRepository userHoldingRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "ledger-group")
    @Transactional
    public void consumeTradeExecution(TradeExecution trade) {
        if (trade.getTradeId() == null) return;

        // Idempotency check against database — survives restarts
        if (processedEventRepository.existsByEventIdAndEventType(
                trade.getTradeId(), "TRADE_EXECUTED")) {
            log.warn("Trade {} already processed, skipping", trade.getTradeId());
            return;
        }

        double amount = trade.getExecutedPrice() * trade.getQuantity();

        // Settle buyer
        if (trade.getBuyerId() != null) {
            UserBalance buyerBalance = userBalanceRepository
                    .findById(trade.getBuyerId())
                    .orElse(new UserBalance(trade.getBuyerId(), BigDecimal.ZERO, Instant.now()));
            buyerBalance.setBalance(
                    buyerBalance.getBalance().subtract(BigDecimal.valueOf(amount)));
            userBalanceRepository.save(buyerBalance);

            UserHoldingId holdingId = new UserHoldingId(trade.getBuyerId(), trade.getSymbol());
            UserHolding holding = userHoldingRepository
                    .findById(holdingId)
                    .orElse(new UserHolding(holdingId, BigDecimal.ZERO, Instant.now()));
            holding.setQuantity(
                    holding.getQuantity().add(BigDecimal.valueOf(trade.getQuantity())));
            userHoldingRepository.save(holding);

            ledgerEntryRepository.save(buildEntry(trade, trade.getBuyerId(),
                    "BUY", amount, buyerBalance.getBalance().doubleValue()));
        }

        // Settle seller
        if (trade.getSellerId() != null) {
            UserBalance sellerBalance = userBalanceRepository
                    .findById(trade.getSellerId())
                    .orElse(new UserBalance(trade.getSellerId(), BigDecimal.ZERO, Instant.now()));
            sellerBalance.setBalance(
                    sellerBalance.getBalance().add(BigDecimal.valueOf(amount)));
            userBalanceRepository.save(sellerBalance);

            UserHoldingId holdingId = new UserHoldingId(trade.getSellerId(), trade.getSymbol());
            UserHolding holding = userHoldingRepository
                    .findById(holdingId)
                    .orElse(new UserHolding(holdingId, BigDecimal.ZERO, Instant.now()));
            holding.setQuantity(
                    holding.getQuantity().subtract(BigDecimal.valueOf(trade.getQuantity())));
            userHoldingRepository.save(holding);

            ledgerEntryRepository.save(buildEntry(trade, trade.getSellerId(),
                    "SELL", amount, sellerBalance.getBalance().doubleValue()));
        }

        // Mark as processed — in same @Transactional so it rolls back together
        processedEventRepository.save(
                new ProcessedEvent(trade.getTradeId(), "TRADE_EXECUTED", Instant.now()));

        log.info("Ledger settled trade {}", trade.getTradeId());
    }

    @KafkaListener(topics = "${kafka.consumer.cancellation-topic}", groupId = "ledger-group")
    @Transactional
    public void consumeCancellation(CancellationEvent event) {
        if (processedEventRepository.existsByEventIdAndEventType(
                event.getOrderId(), "ORDER_CANCELLED")) {
            log.info("Cancellation {} already processed", event.getOrderId());
            return;
        }
        // No cash change on cancellation in ledger service
        // Margin service handles the reservation release
        processedEventRepository.save(
                new ProcessedEvent(event.getOrderId(), "ORDER_CANCELLED", Instant.now()));
        log.info("Cancellation recorded for order {}", event.getOrderId());
    }

    public Map<String, Object> getBalance(String userId) {
        if (userId == null) {
            return Map.of("userId", "unknown", "balance", 0.0);
        }
        double balance = userBalanceRepository.findById(userId)
                .map(ub -> ub.getBalance().doubleValue())
                .orElse(0.0);
        return Map.of(
                "userId", userId,
                "balance", balance
        );
    }

    public Map<String, Double> getHoldings(String userId) {
        if (userId == null) {
            return Map.of();
        }
        return userHoldingRepository.findByIdUserId(userId).stream()
                .collect(Collectors.toMap(
                        h -> h.getId().getSymbol(),
                        h -> h.getQuantity().doubleValue()
                ));
    }

    public List<LedgerEntry> getHistory(String userId) {
        if (userId == null) {
            return List.of();
        }
        return ledgerEntryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> LedgerEntry.builder()
                        .entryId(e.getEntryId())
                        .tradeId(e.getTradeId())
                        .userId(e.getUserId())
                        .side(e.getSide())
                        .symbol(e.getSymbol())
                        .quantity(e.getQuantity().doubleValue())
                        .price(e.getPrice().doubleValue())
                        .amount(e.getAmount().doubleValue())
                        .balanceAfter(e.getBalanceAfter().doubleValue())
                        .timestamp(e.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private LedgerEntryEntity buildEntry(TradeExecution trade, String userId,
                                         String side, double amount, double balanceAfter) {
        String entryId = "LEDGER-" + trade.getTradeId() + "-" + side + "-" + Instant.now().toEpochMilli();
        return new LedgerEntryEntity(
                entryId,
                trade.getTradeId(),
                userId,
                side,
                trade.getSymbol(),
                BigDecimal.valueOf(trade.getQuantity()),
                BigDecimal.valueOf(trade.getExecutedPrice()),
                BigDecimal.valueOf(amount),
                BigDecimal.valueOf(balanceAfter),
                null  // created_at is auto-generated by MySQL
        );
    }
}
