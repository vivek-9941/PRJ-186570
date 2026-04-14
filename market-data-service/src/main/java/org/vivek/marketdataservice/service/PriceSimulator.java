package org.vivek.marketdataservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.marketdataservice.config.KafkaProducerConfig;
import org.vivek.marketdataservice.model.PriceTick;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PriceSimulator {

    private static final Map<String, Double> STARTING_PRICE = new LinkedHashMap<>();
    private static final Map<String, Double> BASE_VOLATILITY = new LinkedHashMap<>();
    private static final double MAX_DRIFT_PERCENT = 0.05;
    private static final long TRADE_FRESHNESS_SECONDS = 5L;
    private static final int MAX_HISTORY_SIZE = 100;

    static {
        STARTING_PRICE.put("INFY", 1800.0);
        STARTING_PRICE.put("TCS", 3500.0);
        STARTING_PRICE.put("RELIANCE", 2900.0);
        STARTING_PRICE.put("HDFC", 1650.0);

        BASE_VOLATILITY.put("INFY", 0.004);
        BASE_VOLATILITY.put("TCS", 0.003);
        BASE_VOLATILITY.put("RELIANCE", 0.0035);
        BASE_VOLATILITY.put("HDFC", 0.0045);
    }

    private final KafkaTemplate<String, PriceTick> marketDataKafkaTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Double> currentPrices = new ConcurrentHashMap<>(STARTING_PRICE);
    private final ConcurrentHashMap<String, PriceTick> latestTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> cumulativeTradeValue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> cumulativeTradeVolume = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastTradeAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<TradeSample>> recentTradeWindow = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<PriceTick>> history = new ConcurrentHashMap<>();

    @KafkaListener(topics = "trade-executed", groupId = "marketdata-group")
    public void onTradeExecuted(TradeExecution trade) {
        String symbol = normalizeSymbol(trade.getSymbol());
        if (symbol == null) {
            return;
        }

        double previous = currentPrices.getOrDefault(symbol, STARTING_PRICE.get(symbol));
        double ltp = round(trade.getExecutedPrice());
        currentPrices.put(symbol, ltp);
        lastTradeAt.put(symbol, Instant.now());

        double tradeValue = trade.getExecutedPrice() * trade.getQuantity();
        cumulativeTradeValue.merge(symbol, tradeValue, Double::sum);
        cumulativeTradeVolume.merge(symbol, trade.getQuantity(), Double::sum);

        recentTradeWindow.computeIfAbsent(symbol, key -> new ConcurrentLinkedDeque<>())
                .addLast(new TradeSample(Instant.now(), trade.getQuantity()));
        pruneOldTrades(symbol, Instant.now());

        double change = round(ltp - previous);
        double changePercent = previous == 0.0d ? 0.0d : round((change / previous) * 100.0d);
        double volume = cumulativeTradeVolume.getOrDefault(symbol, 0.0d);
        double vwap = calculateVwap(symbol, ltp);

        PriceTick tick = PriceTick.builder()
                .symbol(symbol)
                .price(ltp)
                .change(change)
                .changePercent(changePercent)
                .timestamp(Instant.now())
                .volume((int) Math.round(volume))
                .vwap(round(vwap))
                .build();

        publishTick(symbol, tick);
    }

    @Scheduled(fixedRate = 1000)
    public void publishTicks() {
        for (String symbol : STARTING_PRICE.keySet()) {
            if (hasRecentTrade(symbol)) {
                continue;
            }
            PriceTick tick = nextTick(symbol);
            publishTick(symbol, tick);
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

    public List<PriceTick> getHistory(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized == null) {
            return List.of();
        }
        ArrayDeque<PriceTick> ticks = history.get(normalized);
        if (ticks == null) {
            return List.of();
        }
        synchronized (ticks) {
            return new ArrayList<>(ticks);
        }
    }

    public List<String> symbols() {
        return STARTING_PRICE.keySet().stream().toList();
    }

    private PriceTick nextTick(String symbol) {
        double currentPrice = currentPrices.get(symbol);
        double volatility = adjustedVolatility(symbol);
        double randomFactor = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
        double change = currentPrice * volatility * randomFactor;
        double unclampedPrice = currentPrice + change;
        double newPrice = clamp(symbol, unclampedPrice);
        currentPrices.put(symbol, newPrice);

        double actualChange = newPrice - currentPrice;
        double changePercent = (actualChange / currentPrice) * 100.0;
        double vwap = calculateVwap(symbol, newPrice);

        return PriceTick.builder()
                .symbol(symbol)
                .price(round(newPrice))
                .change(round(actualChange))
                .changePercent(round(changePercent))
                .timestamp(Instant.now())
                .volume(ThreadLocalRandom.current().nextInt(100, 10001))
                .vwap(round(vwap))
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
                .vwap(round(price))
                .build();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase();
        return STARTING_PRICE.containsKey(normalized) ? normalized : null;
    }

    private double adjustedVolatility(String symbol) {
        Instant now = Instant.now();
        pruneOldTrades(symbol, now);
        double recentVolume = recentTradeWindow.getOrDefault(symbol, new ConcurrentLinkedDeque<>()).stream()
                .mapToDouble(sample -> sample.quantity)
                .sum();
        double base = BASE_VOLATILITY.get(symbol);
        return base * (recentVolume > 1000.0d ? 0.5d : 1.5d);
    }

    private boolean hasRecentTrade(String symbol) {
        Instant tradeAt = lastTradeAt.get(symbol);
        if (tradeAt == null) {
            return false;
        }
        return tradeAt.isAfter(Instant.now().minus(TRADE_FRESHNESS_SECONDS, ChronoUnit.SECONDS));
    }

    private void pruneOldTrades(String symbol, Instant now) {
        ConcurrentLinkedDeque<TradeSample> window = recentTradeWindow.computeIfAbsent(symbol, key -> new ConcurrentLinkedDeque<>());
        Instant cutoff = now.minus(60, ChronoUnit.SECONDS);
        while (!window.isEmpty()) {
            TradeSample sample = window.peekFirst();
            if (sample == null || !sample.timestamp.isBefore(cutoff)) {
                break;
            }
            window.pollFirst();
        }
    }

    private double calculateVwap(String symbol, double fallbackPrice) {
        double volume = cumulativeTradeVolume.getOrDefault(symbol, 0.0d);
        if (volume <= 0.0d) {
            return fallbackPrice;
        }
        double value = cumulativeTradeValue.getOrDefault(symbol, 0.0d);
        return value / volume;
    }

    private void publishTick(String symbol, PriceTick tick) {
        latestTicks.put(symbol, tick);
        addToHistory(symbol, tick);
        marketDataKafkaTemplate.send(KafkaProducerConfig.TOPIC_MARKET_DATA, symbol, tick);
        messagingTemplate.convertAndSend("/topic/prices/" + symbol, tick);
    }

    private void addToHistory(String symbol, PriceTick tick) {
        ArrayDeque<PriceTick> ticks = history.computeIfAbsent(symbol, key -> new ArrayDeque<>());
        synchronized (ticks) {
            ticks.addLast(tick);
            while (ticks.size() > MAX_HISTORY_SIZE) {
                ticks.pollFirst();
            }
        }
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

    private record TradeSample(Instant timestamp, double quantity) {
    }
}
