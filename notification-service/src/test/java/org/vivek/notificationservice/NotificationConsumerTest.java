package org.vivek.notificationservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.vivek.commonmodule.model.OrderExpiredEvent;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock
    private NotificationHandler notificationHandler;

    @Test
    void consumeOrderExpiredStoresExpectedGtdMessage() {
        NotificationConsumer consumer = new NotificationConsumer(notificationHandler);
        OrderExpiredEvent event = OrderExpiredEvent.builder()
                .orderId("ORD-1")
                .userId("user-1")
                .symbol("AAPL")
                .expiredAt(Instant.parse("2026-04-04T11:30:00Z"))
                .build();

        consumer.consumeOrderExpired(event);

        assertEquals(
                "Your GTD order ORD-1 for AAPL expired unfilled at end of day",
                consumer.getNotifications("user-1").get(0)
        );
    }

    @Test
    void duplicateTradeIsIgnored() {
        NotificationConsumer consumer = new NotificationConsumer(notificationHandler);
        var trade = org.vivek.commonmodule.model.TradeExecution.builder()
                .tradeId("TRD-1")
                .buyOrderId("BUY-1")
                .buyerId("user-1")
                .symbol("AAPL")
                .quantity(1.0d)
                .executedPrice(100.0d)
                .executedAt(Instant.now())
                .build();

        consumer.consumeTradeExecution(trade);
        consumer.consumeTradeExecution(trade);

        assertEquals(1, consumer.getNotifications("user-1").size());
    }
}
