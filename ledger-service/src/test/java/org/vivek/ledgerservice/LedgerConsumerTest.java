package org.vivek.ledgerservice;

import org.junit.jupiter.api.Test;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.TradeExecution;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerConsumerTest {

    @Test
    void consumeCancellationReleasesReservedMargin() {
        LedgerConsumer consumer = new LedgerConsumer();
        consumer.reserveMargin("ORD-1", 250.0d);

        consumer.consumeCancellation(CancellationEvent.builder()
                .orderId("ORD-1")
                .userId("user-1")
                .symbol("AAPL")
                .cancelledAt(Instant.now())
                .build());

        assertEquals(250.0d, consumer.getBalance("user-1").get("balance"));
    }

    @Test
    void consumeTradeExecutionUpdatesBuyerAndSellerBalances() {
        LedgerConsumer consumer = new LedgerConsumer();

        consumer.consumeTradeExecution(TradeExecution.builder()
                .tradeId("TRD-1")
                .buyOrderId("BUY-1")
                .sellOrderId("SELL-1")
                .buyerId("buyer")
                .sellerId("seller")
                .symbol("AAPL")
                .quantity(2.0d)
                .executedPrice(100.0d)
                .executedAt(Instant.now())
                .build());

        assertEquals(-200.0d, consumer.getBalance("buyer").get("balance"));
        assertEquals(200.0d, consumer.getBalance("seller").get("balance"));
    }
}
