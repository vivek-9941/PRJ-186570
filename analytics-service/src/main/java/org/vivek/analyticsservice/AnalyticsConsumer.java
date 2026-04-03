package org.vivek.analyticsservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.TradeExecution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AnalyticsConsumer {

    private final Map<String, SymbolStats> symbolStatsMap = new ConcurrentHashMap<>();

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "analytics-group")
    public void consumeTradeExecution(TradeExecution trade) {
        String symbol = trade.getSymbol();
        
        symbolStatsMap.compute(symbol, (k, stats) -> {
            if (stats == null) {
                return new SymbolStats(1, trade.getQuantity(), trade.getExecutedPrice(), trade.getExecutedPrice());
            } else {
                long newTotalTrades = stats.getTotalTrades() + 1;
                double newTotalVolume = stats.getTotalVolume() + trade.getQuantity();
                double newAvgPrice = ((stats.getAvgPrice() * stats.getTotalTrades()) + trade.getExecutedPrice()) / newTotalTrades;
                
                stats.setTotalTrades(newTotalTrades);
                stats.setTotalVolume(newTotalVolume);
                stats.setLastPrice(trade.getExecutedPrice());
                stats.setAvgPrice(newAvgPrice);
                return stats;
            }
        });

        log.info("Analytics updated for symbol {}: {}", symbol, symbolStatsMap.get(symbol));
    }

    public Map<String, SymbolStats> getAllStats() {
        return symbolStatsMap;
    }

    public SymbolStats getStats(String symbol) {
        return symbolStatsMap.get(symbol);
    }
}
