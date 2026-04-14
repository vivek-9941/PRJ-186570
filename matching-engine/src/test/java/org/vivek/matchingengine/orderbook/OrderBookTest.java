package org.vivek.matchingengine.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.commonmodule.model.TradeExecution;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private static final String SYMBOL = "INFY";
    private SymbolOrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new SymbolOrderBook(SYMBOL);
    }

    @Test
    void buy_order_matches_best_ask_first() {
        orderBook.match(sell("S1", 100.0, 50.0));

        List<TradeExecution> executions = orderBook.match(buy("B1", 102.0, 50.0));

        assertEquals(1, executions.size());
        assertEquals(100.0, executions.get(0).getExecutedPrice());
        assertEquals(50.0, executions.get(0).getQuantity());
        assertTrue(sellOrders().isEmpty());
    }

    @Test
    void no_match_when_buy_below_ask() {
        orderBook.match(sell("S1", 105.0, 50.0));

        List<TradeExecution> executions = orderBook.match(buy("B1", 100.0, 50.0));

        assertTrue(executions.isEmpty());
        assertEquals(1, buyOrders().size());
        assertEquals(100.0, buyOrders().firstKey());
        assertEquals(1, sellOrders().size());
        assertEquals(105.0, sellOrders().firstKey());
    }

    @Test
    void partial_fill_requeues_remainder() {
        orderBook.match(sell("S1", 100.0, 30.0));

        Order incomingBuy = buy("B1", 100.0, 100.0);
        List<TradeExecution> executions = orderBook.match(incomingBuy);

        assertEquals(1, executions.size());
        assertEquals(30.0, executions.get(0).getQuantity());
        assertEquals(70.0, incomingBuy.getQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, incomingBuy.getStatus());

        ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buys = buyOrders();
        assertEquals(1, buys.size());
        assertEquals(100.0, buys.firstKey());
        Order restingRemainder = buys.firstEntry().getValue().peek();
        assertEquals(70.0, restingRemainder.getQuantity());
    }

    @Test
    void multiple_levels_are_consumed_in_price_time_order() {
        orderBook.match(sell("S1", 100.0, 40.0));
        orderBook.match(sell("S2", 101.0, 40.0));

        List<TradeExecution> executions = orderBook.match(buy("B1", 101.0, 80.0));

        assertEquals(2, executions.size());
        assertEquals(100.0, executions.get(0).getExecutedPrice());
        assertEquals(40.0, executions.get(0).getQuantity());
        assertEquals(101.0, executions.get(1).getExecutedPrice());
        assertEquals(40.0, executions.get(1).getQuantity());
        assertTrue(sellOrders().isEmpty());
    }

    @Test
    void ioc_remainder_is_not_queued() {
        orderBook.match(sell("S1", 100.0, 30.0));

        List<TradeExecution> executions = orderBook.match(buy("B1", 100.0, 100.0, OrderType.IOC));

        assertEquals(1, executions.size());
        assertEquals(30.0, executions.get(0).getQuantity());
        assertTrue(buyOrders().isEmpty());
    }

    @Test
    void cancel_removes_order_from_book() {
        orderBook.match(buy("B1", 99.0, 10.0));

        assertTrue(orderBook.cancel("B1"));
        assertTrue(buyOrders().isEmpty());
        assertFalse(orderBook.cancel("B1"));
    }

    @Test
    void concurrent_orders_no_data_corruption() throws Exception {
        int count = 50;
        double qtyPerOrder = 10.0;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<List<TradeExecution>>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                tasks.add(() -> orderBook.match(buy("B" + index, 100.0, qtyPerOrder)));
                tasks.add(() -> orderBook.match(sell("S" + index, 100.0, qtyPerOrder)));
            }

            List<Future<List<TradeExecution>>> futures = pool.invokeAll(tasks);

            double totalFilledQty = 0.0;
            for (Future<List<TradeExecution>> future : futures) {
                for (TradeExecution execution : future.get(5, TimeUnit.SECONDS)) {
                    totalFilledQty += execution.getQuantity();
                }
            }

            double expected = Math.min(count * qtyPerOrder, count * qtyPerOrder);
            assertEquals(expected, totalFilledQty, 0.0001);
        } finally {
            pool.shutdownNow();
        }
    }

    private Order buy(String id, double price, double quantity) {
        return buy(id, price, quantity, OrderType.LIMIT);
    }

    private Order buy(String id, double price, double quantity, OrderType orderType) {
        return Order.builder()
                .orderId(id)
                .userId("BUYER")
                .symbol(SYMBOL)
                .side(OrderSide.BUY)
                .price(price)
                .quantity(quantity)
                .orderType(orderType)
                .status(OrderStatus.PENDING)
                .build();
    }

    private Order sell(String id, double price, double quantity) {
        return Order.builder()
                .orderId(id)
                .userId("SELLER")
                .symbol(SYMBOL)
                .side(OrderSide.SELL)
                .price(price)
                .quantity(quantity)
                .orderType(OrderType.LIMIT)
                .status(OrderStatus.PENDING)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buyOrders() {
        return (ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>>) getField("buyOrders");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> sellOrders() {
        return (ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>>) getField("sellOrders");
    }

    private Object getField(String name) {
        try {
            Field field = SymbolOrderBook.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(orderBook);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
