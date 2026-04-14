package org.vivek.marketdataservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.vivek.marketdataservice.model.PriceTick;
import org.vivek.marketdataservice.service.PriceSimulator;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final PriceSimulator priceSimulator;

    @GetMapping
    public ResponseEntity<Map<String, PriceTick>> getAllPrices() {
        return ResponseEntity.ok(priceSimulator.getAllLatestTicks());
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<?> getPriceBySymbol(@PathVariable String symbol) {
        PriceTick tick = priceSimulator.getLatestTick(symbol);
        if (tick == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown symbol. Supported symbols: " + String.join(", ", priceSimulator.symbols())
            ));
        }
        return ResponseEntity.ok(tick);
    }

    @GetMapping("/{symbol}/history")
    public ResponseEntity<?> getPriceHistory(@PathVariable String symbol) {
        PriceTick latestTick = priceSimulator.getLatestTick(symbol);
        if (latestTick == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown symbol. Supported symbols: " + String.join(", ", priceSimulator.symbols())
            ));
        }
        List<PriceTick> history = priceSimulator.getHistory(symbol);
        return ResponseEntity.ok(history);
    }
}
