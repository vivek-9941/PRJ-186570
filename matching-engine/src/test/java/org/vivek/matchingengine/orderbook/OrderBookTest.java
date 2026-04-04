package org.vivek.matchingengine.orderbook;

import org.junit.jupiter.api.Test;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.TradeExecution;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private final OrderBook orderBook = new OrderBook();

    @Test
    void matchCreatesMultipleExecutionsAndPreservesRemainders() {
        Order sellOne = order("sell-1", OrderSide.SELL, 3.0d, 100.0d);
        Order sellTwo = order("sell-2", OrderSide.SELL, 4.0d, 101.0d);

        assertTrue(orderBook.match(sellOne).isEmpty());
        assertTrue(orderBook.match(sellTwo).isEmpty());

        Order incomingBuy = order("buy-1", OrderSide.BUY, 5.5d, 101.0d);
        List<TradeExecution> executions = orderBook.match(incomingBuy);

        assertEquals(2, executions.size());
        assertEquals(3.0d, executions.get(0).getQuantity());
        assertEquals(100.0d, executions.get(0).getExecutedPrice());
        assertEquals(2.5d, executions.get(1).getQuantity());
        assertEquals(101.0d, executions.get(1).getExecutedPrice());
        assertEquals(OrderStatus.FULLY_FILLED, incomingBuy.getStatus());
        assertEquals(0.0d, incomingBuy.getQuantity());

        Order followUpBuy = order("buy-2", OrderSide.BUY, 2.0d, 101.0d);
        List<TradeExecution> followUpExecutions = orderBook.match(followUpBuy);

        assertEquals(1, followUpExecutions.size());
        assertEquals(1.5d, followUpExecutions.get(0).getQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, followUpBuy.getStatus());
        assertEquals(0.5d, followUpBuy.getQuantity());
    }

    @Test
    void matchRequeuesIncomingRemainderAfterPartialFill() {
        Order restingSell = order("sell-1", OrderSide.SELL, 2.0d, 100.0d);
        assertTrue(orderBook.match(restingSell).isEmpty());

        Order incomingBuy = order("buy-1", OrderSide.BUY, 5.0d, 101.0d);
        List<TradeExecution> executions = orderBook.match(incomingBuy);

        assertEquals(1, executions.size());
        assertEquals(2.0d, executions.get(0).getQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, incomingBuy.getStatus());
        assertEquals(3.0d, incomingBuy.getQuantity());

        Order sellToHitRemainder = order("sell-2", OrderSide.SELL, 1.5d, 101.0d);
        List<TradeExecution> remainderExecutions = orderBook.match(sellToHitRemainder);

        assertEquals(1, remainderExecutions.size());
        assertEquals(1.5d, remainderExecutions.get(0).getQuantity());
        assertEquals("buy-1", remainderExecutions.get(0).getBuyOrderId());
    }

    private Order order(String orderId, OrderSide side, double quantity, double price) {
        return Order.builder()
                .orderId(orderId)
                .userId(orderId + "-user")
                .symbol("AAPL")
                .side(side)
                .quantity(quantity)
                .price(price)
                .status(OrderStatus.PENDING)
                .build();
    }
}
