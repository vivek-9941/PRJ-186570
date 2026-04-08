package org.vivek.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderExpiredEvent;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.order.client.MatchingEngineClient;
import org.vivek.order.config.OrderKafkaProducerConfig;
import org.vivek.order.repository.OrderRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpirySchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MatchingEngineClient matchingEngineClient;

    @Mock
    private KafkaTemplate<String, OrderExpiredEvent> orderExpiredKafkaTemplate;

    @Test
    void expireGtdOrdersExpiresAllPendingAndPartiallyFilledOrders() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-04T11:30:00Z"), ZoneId.of("Asia/Calcutta"));
        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                orderRepository,
                matchingEngineClient,
                orderExpiredKafkaTemplate,
                fixedClock
        );

        Order pending = order("ORD-1", OrderStatus.PENDING, Instant.parse("2026-04-04T09:00:00Z"), null);
        Order partial = order("ORD-2", OrderStatus.PARTIALLY_FILLED, Instant.parse("2026-04-04T09:30:00Z"), null);
        when(orderRepository.findByStatusInAndOrderType(List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), OrderType.GTD))
                .thenReturn(List.of(pending, partial));

        scheduler.expireGTDOrders();

        verify(matchingEngineClient).cancel("ORD-1");
        verify(matchingEngineClient).cancel("ORD-2");
        verify(orderRepository).save(pending);
        verify(orderRepository).save(partial);
        verify(orderExpiredKafkaTemplate).send(eq(OrderKafkaProducerConfig.TOPIC_ORDER_EXPIRED), eq("ORD-1"), eq(eventFor("ORD-1", "user-1", "AAPL", fixedClock.instant())));
        verify(orderExpiredKafkaTemplate).send(eq(OrderKafkaProducerConfig.TOPIC_ORDER_EXPIRED), eq("ORD-2"), eq(eventFor("ORD-2", "user-1", "AAPL", fixedClock.instant())));
        assertEquals(OrderStatus.EXPIRED, pending.getStatus());
        assertEquals(OrderStatus.EXPIRED, partial.getStatus());
    }

    @Test
    void expireStaleOrdersOnlyExpiresOrdersOlderThanTodayDuringTradingHours() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-03T05:00:00Z"), ZoneId.of("Asia/Calcutta"));
        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                orderRepository,
                matchingEngineClient,
                orderExpiredKafkaTemplate,
                fixedClock
        );

        Order stale = order("ORD-STALE", OrderStatus.PENDING, Instant.parse("2026-04-02T04:00:00Z"), null);
        Order today = order("ORD-TODAY", OrderStatus.PENDING, Instant.parse("2026-04-03T04:00:00Z"), null);
        when(orderRepository.findByStatusInAndOrderType(List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), OrderType.GTD))
                .thenReturn(List.of(stale, today));

        scheduler.expireStaleOrders();

        verify(matchingEngineClient).cancel("ORD-STALE");
        verify(matchingEngineClient, never()).cancel("ORD-TODAY");
        assertEquals(OrderStatus.EXPIRED, stale.getStatus());
        assertEquals(OrderStatus.PENDING, today.getStatus());
    }

    @Test
    void expireStaleOrdersRespectsExplicitExpiryTime() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-03T05:30:00Z"), ZoneId.of("Asia/Calcutta"));
        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                orderRepository,
                matchingEngineClient,
                orderExpiredKafkaTemplate,
                fixedClock
        );

        Order expired = order(
                "ORD-EXP",
                OrderStatus.PENDING,
                Instant.parse("2026-04-03T04:00:00Z"),
                LocalDateTime.of(2026, 4, 3, 10, 0)
        );
        when(orderRepository.findByStatusInAndOrderType(List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), OrderType.GTD))
                .thenReturn(List.of(expired));

        scheduler.expireStaleOrders();

        verify(matchingEngineClient).cancel("ORD-EXP");
        assertEquals(OrderStatus.EXPIRED, expired.getStatus());
    }

    private Order order(String orderId, OrderStatus status, Instant createdAt, LocalDateTime expiryTime) {
        return Order.builder()
                .orderId(orderId)
                .userId("user-1")
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .orderType(OrderType.GTD)
                .quantity(5.0d)
                .price(100.0d)
                .status(status)
                .createdAt(createdAt)
                .expiryTime(expiryTime)
                .build();
    }

    private OrderExpiredEvent eventFor(String orderId, String userId, String symbol, Instant expiredAt) {
        return OrderExpiredEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .symbol(symbol)
                .expiredAt(expiredAt)
                .build();
    }
}
