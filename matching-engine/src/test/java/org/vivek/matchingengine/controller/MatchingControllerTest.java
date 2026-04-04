package org.vivek.matchingengine.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.matchingengine.config.KafkaProducerConfig;
import org.vivek.matchingengine.orderbook.OrderBook;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingControllerTest {

    @Mock
    private OrderBook orderBook;

    @Mock
    private KafkaTemplate<String, TradeExecution> kafkaTemplate;

    @Mock
    private KafkaTemplate<String, CancellationEvent> cancellationKafkaTemplate;

    @Test
    void matchReturnsAggregatedResponseAndPublishesEachExecution() {
        MatchingController controller = new MatchingController(orderBook, kafkaTemplate, cancellationKafkaTemplate);
        Order order = Order.builder()
                .orderId("buy-1")
                .userId("buyer")
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .quantity(5.0d)
                .price(101.0d)
                .status(OrderStatus.PENDING)
                .build();

        List<TradeExecution> executions = List.of(
                execution("trade-1", "buy-1", "sell-1", 2.0d, 100.0d),
                execution("trade-2", "buy-1", "sell-2", 1.5d, 100.5d)
        );

        when(orderBook.match(order)).thenReturn(executions);
        when(kafkaTemplate.send(eq(KafkaProducerConfig.TOPIC_TRADE_EXECUTED), eq("buy-1"), any(TradeExecution.class)))
                .thenReturn(new CompletableFuture<>());

        ResponseEntity<Map<String, Object>> response = controller.match(order);

        assertTrue((Boolean) response.getBody().get("matched"));
        assertEquals(2, response.getBody().get("fillCount"));
        assertEquals(3.5d, (Double) response.getBody().get("totalFilled"));
        assertEquals(1.5d, (Double) response.getBody().get("remainingQty"));
        assertEquals(executions, response.getBody().get("executions"));
        verify(kafkaTemplate, times(2)).send(eq(KafkaProducerConfig.TOPIC_TRADE_EXECUTED), eq("buy-1"), any(TradeExecution.class));
    }

    @Test
    void matchReturnsUnmatchedResponseWhenNoExecutionOccurs() {
        MatchingController controller = new MatchingController(orderBook, kafkaTemplate, cancellationKafkaTemplate);
        Order order = Order.builder()
                .orderId("sell-1")
                .userId("seller")
                .symbol("AAPL")
                .side(OrderSide.SELL)
                .quantity(4.0d)
                .price(102.0d)
                .status(OrderStatus.PENDING)
                .build();

        when(orderBook.match(order)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.match(order);

        assertFalse((Boolean) response.getBody().get("matched"));
        assertEquals(0, response.getBody().get("fillCount"));
        assertEquals(0.0d, (Double) response.getBody().get("totalFilled"));
        assertEquals(4.0d, (Double) response.getBody().get("remainingQty"));
        assertEquals(List.of(), response.getBody().get("executions"));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void cancelPublishesCancellationEventWhenOrderIsRemoved() {
        MatchingController controller = new MatchingController(orderBook, kafkaTemplate, cancellationKafkaTemplate);
        Order cancelledOrder = Order.builder()
                .orderId("ord-1")
                .userId("user-1")
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .quantity(3.0d)
                .price(100.0d)
                .status(OrderStatus.PENDING)
                .build();

        when(orderBook.cancelOrder("ord-1")).thenReturn(cancelledOrder);
        when(cancellationKafkaTemplate.send(eq(KafkaProducerConfig.TOPIC_ORDER_CANCELLED), eq("ord-1"), any(CancellationEvent.class)))
                .thenReturn(new CompletableFuture<>());

        ResponseEntity<Map<String, Object>> response = controller.cancel("ord-1");

        assertTrue((Boolean) response.getBody().get("cancelled"));
        verify(cancellationKafkaTemplate).send(eq(KafkaProducerConfig.TOPIC_ORDER_CANCELLED), eq("ord-1"), any(CancellationEvent.class));
    }

    @Test
    void cancelReturnsFalseWhenOrderIsNotFoundInBook() {
        MatchingController controller = new MatchingController(orderBook, kafkaTemplate, cancellationKafkaTemplate);

        when(orderBook.cancelOrder("ord-2")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.cancel("ord-2");

        assertFalse((Boolean) response.getBody().get("cancelled"));
        verifyNoInteractions(cancellationKafkaTemplate);
    }

    private TradeExecution execution(String tradeId, String buyOrderId, String sellOrderId,
                                     double quantity, double executedPrice) {
        return TradeExecution.builder()
                .tradeId(tradeId)
                .buyOrderId(buyOrderId)
                .sellOrderId(sellOrderId)
                .buyerId("buyer")
                .sellerId("seller")
                .symbol("AAPL")
                .quantity(quantity)
                .executedPrice(executedPrice)
                .executedAt(Instant.now())
                .build();
    }
}
