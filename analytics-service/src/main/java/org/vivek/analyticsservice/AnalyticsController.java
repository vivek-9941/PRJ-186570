package org.vivek.analyticsservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsConsumer analyticsConsumer;

    public AnalyticsController(AnalyticsConsumer analyticsConsumer) {
        this.analyticsConsumer = analyticsConsumer;
    }

    @GetMapping("/symbols")
    public Map<String, SymbolStats> getAllStats() {
        return analyticsConsumer.getAllStats();
    }

    @GetMapping("/symbols/{symbol}")
    public SymbolStats getStats(@PathVariable String symbol) {
        return analyticsConsumer.getStats(symbol);
    }
}
