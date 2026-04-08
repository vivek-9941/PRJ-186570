package org.vivek.marketdataservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.vivek.marketdataservice.config.KafkaProducerConfig;
import org.vivek.marketdataservice.model.PriceTick;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PriceSimulator {

    private static final Map<String, Double> STARTING_PRICE = new LinkedHashMap<>();
    private static final Map<String, Double> VOLATILITY = new LinkedHashMap<>();
    private static final double MAX_DRIFT_PERCENT = 0.05;

    static {
        STARTING_PRICE.put("INFY", 1800.0);
        STARTING_PRICE.put("TCS", 3500.0);
        STARTING_PRICE.put("RELIANCE", 2900.0);
        STARTING_PRICE.put("HDFC", 1650.0);

        VOLATILITY.put("INFY", 0.004);
        VOLATILITY.put("TCS", 0.003);
        VOLATILITY.put("RELIANCE", 0.0035);
        VOLATILITY.put("HDFC", 0.0045);
    }

    private final KafkaTemplate<String, PriceTick> marketDataKafkaTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Double> currentPrices = new ConcurrentHashMap<>(STARTING_PRICE);
    private final ConcurrentHashMap<String, PriceTick> latestTicks = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 1000)
    public void publishTicks() {
        for (String symbol : STARTING_PRICE.keySet()) {
            PriceTick tick = nextTick(symbol);
            latestTicks.put(symbol, tick);
            marketDataKafkaTemplate.send(KafkaProducerConfig.TOPIC_MARKET_DATA, symbol, tick);
            messagingTemplate.convertAndSend("/topic/prices/" + symbol, tick);
        }
    }

    public Map<String, PriceTick> getAllLatestTicks() {
        Map<String, PriceTick> ordered = new LinkedHashMap<>();
        for (String symbol : STARTING_PRICE.keySet()) {
            ordered.put(symbol, latestTicks.computeIfAbsent(symbol, this::initialTick));
        }
        return ordered;
    }

    public PriceTick getLatestTick(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized == null) {
            return null;
        }
        return latestTicks.computeIfAbsent(normalized, this::initialTick);
    }

    public List<String> symbols() {
        return STARTING_PRICE.keySet().stream().toList();
    }

    private PriceTick nextTick(String symbol) {
        double currentPrice = currentPrices.get(symbol);
        double volatility = VOLATILITY.get(symbol);
        double randomFactor = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
        double change = currentPrice * volatility * randomFactor;
        double unclampedPrice = currentPrice + change;
        double newPrice = clamp(symbol, unclampedPrice);
        currentPrices.put(symbol, newPrice);

        double actualChange = newPrice - currentPrice;
        double changePercent = (actualChange / currentPrice) * 100.0;

        return PriceTick.builder()
                .symbol(symbol)
                .price(round(newPrice))
                .change(round(actualChange))
                .changePercent(round(changePercent))
                .timestamp(Instant.now())
                .volume(ThreadLocalRandom.current().nextInt(100, 10001))
                .build();
    }

    private PriceTick initialTick(String symbol) {
        Double price = STARTING_PRICE.get(symbol);
        if (price == null) {
            return null;
        }
        return PriceTick.builder()
                .symbol(symbol)
                .price(round(price))
                .change(0.0)
                .changePercent(0.0)
                .timestamp(Instant.now())
                .volume(ThreadLocalRandom.current().nextInt(100, 10001))
                .build();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase();
        return STARTING_PRICE.containsKey(normalized) ? normalized : null;
    }

    private double clamp(String symbol, double candidatePrice) {
        double basePrice = STARTING_PRICE.get(symbol);
        double min = basePrice * (1 - MAX_DRIFT_PERCENT);
        double max = basePrice * (1 + MAX_DRIFT_PERCENT);
        return Math.max(min, Math.min(max, candidatePrice));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}