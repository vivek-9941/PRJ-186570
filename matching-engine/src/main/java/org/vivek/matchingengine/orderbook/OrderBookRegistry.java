package org.vivek.matchingengine.orderbook;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.commonmodule.model.CancellationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookRegistry {

    private static final List<String> DEFAULT_SYMBOLS = List.of("INFY", "TCS", "RELIANCE", "HDFC");
    private static final String LIQUIDITY_PROVIDER_USER = "LP_BOOTSTRAP";

    private final KafkaTemplate<String, CancellationEvent> cancellationKafkaTemplate;
    private final ConcurrentHashMap<String, SymbolOrderBook> books = new ConcurrentHashMap<>();

    @Value("${matching.orderbook.bootstrap.orders-per-symbol:10}")
    private int bootstrapOrdersPerSymbol;

    @Value("${matching.orderbook.bootstrap.price-step-ratio:0.001}")
    private double bootstrapPriceStepRatio;

    @PostConstruct
    public void init() {
        DEFAULT_SYMBOLS.forEach(symbol -> {
            SymbolOrderBook book = getBook(symbol);
            seedBootstrapLiquidity(book, symbol);
        });
    }

    public SymbolOrderBook getBook(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return books.computeIfAbsent(normalized, key -> new SymbolOrderBook(key, cancellationKafkaTemplate));
    }

    public List<BookSnapshot> getAllSnapshots() {
        return books.values().stream()
                .map(SymbolOrderBook::snapshot)
                .sorted((left, right) -> left.getSymbol().compareTo(right.getSymbol()))
                .toList();
    }

    public Map<String, SymbolOrderBook> getBooks() {
        return books;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        return symbol.trim().toUpperCase();
    }

    private void seedBootstrapLiquidity(SymbolOrderBook book, String symbol) {
        if (bootstrapOrdersPerSymbol <= 0) {
            return;
        }

        double basePrice = basePriceFor(symbol);
        Instant now = Instant.now();

        for (int index = 1; index <= bootstrapOrdersPerSymbol; index++) {
            double price = roundTo2(basePrice * (1.0d + (bootstrapPriceStepRatio * index)));
            double quantity = 10.0d + index;

            Order syntheticSell = Order.builder()
                    .orderId("LP-" + symbol + "-" + index)
                    .userId(LIQUIDITY_PROVIDER_USER)
                    .symbol(symbol)
                    .side(OrderSide.SELL)
                    .quantity(quantity)
                    .price(price)
                    .orderType(OrderType.LIMIT)
                    .status(OrderStatus.PENDING)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            book.addRestingOrder(syntheticSell);
        }

        log.info("Seeded {} bootstrap SELL orders for symbol {}", bootstrapOrdersPerSymbol, symbol);
    }

    private double basePriceFor(String symbol) {
        return switch (symbol) {
            case "INFY" -> 1775.0d;
            case "TCS" -> 3725.0d;
            case "RELIANCE" -> 2920.0d;
            case "HDFC" -> 1650.0d;
            default -> 1000.0d;
        };
    }

    private double roundTo2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
