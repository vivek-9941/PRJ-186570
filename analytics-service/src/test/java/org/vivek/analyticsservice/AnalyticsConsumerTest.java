package org.vivek.analyticsservice;

import org.junit.jupiter.api.Test;
import org.vivek.commonmodule.model.TradeExecution;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyticsConsumerTest {

    @Test
    void duplicateTradeIsIgnored() {
        AnalyticsConsumer consumer = new AnalyticsConsumer();
        TradeExecution trade = TradeExecution.builder()
                .tradeId("TRD-1")
                .symbol("AAPL")
                .quantity(2.0d)
                .executedPrice(100.0d)
                .executedAt(Instant.now())
                .build();

        consumer.consumeTradeExecution(trade);
        consumer.consumeTradeExecution(trade);

        assertEquals(1L, consumer.getStats("AAPL").getTotalTrades());
        assertEquals(2.0d, consumer.getStats("AAPL").getTotalVolume());
    }
}
