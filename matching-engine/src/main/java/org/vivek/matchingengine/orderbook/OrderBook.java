package org.vivek.matchingengine.orderbook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.TradeExecution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

@Component
@Slf4j
public class OrderBook {

    private static final double EPSILON = 1e-9;

    // Descending: highest bid first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buyOrders =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    // Ascending: lowest ask first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> sellOrders =
            new ConcurrentSkipListMap<>();

    public synchronized List<TradeExecution> match(Order incomingOrder) {
        double remainingQty = incomingOrder.getQuantity();
        List<TradeExecution> executions = new ArrayList<>();

        while (remainingQty > EPSILON) {
            if (incomingOrder.getSide() == OrderSide.BUY) {
                Double bestAsk = sellOrders.isEmpty() ? null : sellOrders.firstKey();
                if (bestAsk == null || bestAsk > incomingOrder.getPrice()) {
                    break;
                }

                remainingQty = executeIncomingBuy(incomingOrder, remainingQty, executions, bestAsk);
            } else {
                Double bestBid = buyOrders.isEmpty() ? null : buyOrders.firstKey();
                if (bestBid == null || bestBid < incomingOrder.getPrice()) {
                    break;
                }

                remainingQty = executeIncomingSell(incomingOrder, remainingQty, executions, bestBid);
            }
        }

        applyIncomingOrderState(incomingOrder, remainingQty, executions);
        return executions;
    }

    private double executeIncomingBuy(Order incomingBuy, double remainingQty,
                                      List<TradeExecution> executions, double bestAsk) {
        ConcurrentLinkedQueue<Order> queue = sellOrders.get(bestAsk);
        if (queue == null) {
            sellOrders.remove(bestAsk);
            return remainingQty;
        }

        Order restingSell = queue.peek();
        if (restingSell == null) {
            sellOrders.remove(bestAsk);
            return remainingQty;
        }

        double fillQty = Math.min(remainingQty, restingSell.getQuantity());
        if (fillQty <= EPSILON) {
            queue.poll();
            if (queue.isEmpty()) {
                sellOrders.remove(bestAsk);
            }
            return remainingQty;
        }

        TradeExecution trade = buildTrade(incomingBuy, restingSell, bestAsk, fillQty);
        executions.add(trade);

        remainingQty = normalizeQuantity(remainingQty - fillQty);
        reduceRestingOrder(bestAsk, queue, restingSell, fillQty, sellOrders);

        log.info("Matched BUY {} with SELL {} at price {} for qty {} symbol {}",
                incomingBuy.getOrderId(), restingSell.getOrderId(), bestAsk, fillQty, incomingBuy.getSymbol());
        return remainingQty;
    }

    private double executeIncomingSell(Order incomingSell, double remainingQty,
                                       List<TradeExecution> executions, double bestBid) {
        ConcurrentLinkedQueue<Order> queue = buyOrders.get(bestBid);
        if (queue == null) {
            buyOrders.remove(bestBid);
            return remainingQty;
        }

        Order restingBuy = queue.peek();
        if (restingBuy == null) {
            buyOrders.remove(bestBid);
            return remainingQty;
        }

        double fillQty = Math.min(remainingQty, restingBuy.getQuantity());
        if (fillQty <= EPSILON) {
            queue.poll();
            if (queue.isEmpty()) {
                buyOrders.remove(bestBid);
            }
            return remainingQty;
        }

        TradeExecution trade = buildTrade(restingBuy, incomingSell, bestBid, fillQty);
        executions.add(trade);

        remainingQty = normalizeQuantity(remainingQty - fillQty);
        reduceRestingOrder(bestBid, queue, restingBuy, fillQty, buyOrders);

        log.info("Matched SELL {} with BUY {} at price {} for qty {} symbol {}",
                incomingSell.getOrderId(), restingBuy.getOrderId(), bestBid, fillQty, incomingSell.getSymbol());
        return remainingQty;
    }

    private void addToBook(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            buyOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).add(order);
            log.info("Added BUY order {} to book at price {}", order.getOrderId(), order.getPrice());
        } else {
            sellOrders.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).add(order);
            log.info("Added SELL order {} to book at price {}", order.getOrderId(), order.getPrice());
        }
    }

    private void applyIncomingOrderState(Order incomingOrder, double remainingQty, List<TradeExecution> executions) {
        Instant updatedAt = Instant.now();
        boolean matched = !executions.isEmpty();

        if (remainingQty > EPSILON) {
            Order remainder = incomingOrder.withQuantity(remainingQty);
            remainder.setStatus(matched ? OrderStatus.PARTIALLY_FILLED : OrderStatus.PENDING);
            remainder.setUpdatedAt(updatedAt);
            addToBook(remainder);

            incomingOrder.setQuantity(remainingQty);
            incomingOrder.setStatus(remainder.getStatus());
            incomingOrder.setUpdatedAt(updatedAt);
            return;
        }

        incomingOrder.setQuantity(0.0d);
        incomingOrder.setStatus(matched ? OrderStatus.FULLY_FILLED : incomingOrder.getStatus());
        incomingOrder.setUpdatedAt(updatedAt);
    }

    private void reduceRestingOrder(Double priceLevel, ConcurrentLinkedQueue<Order> queue, Order restingOrder,
                                    double fillQty, ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> bookSide) {
        double updatedQty = normalizeQuantity(restingOrder.getQuantity() - fillQty);
        restingOrder.setQuantity(updatedQty);
        restingOrder.setUpdatedAt(Instant.now());

        if (updatedQty <= EPSILON) {
            restingOrder.setStatus(OrderStatus.FULLY_FILLED);
            queue.poll();
            if (queue.isEmpty()) {
                bookSide.remove(priceLevel);
            }
            return;
        }

        restingOrder.setStatus(OrderStatus.PARTIALLY_FILLED);
    }

    private double normalizeQuantity(double quantity) {
        return quantity <= EPSILON ? 0.0d : quantity;
    }

    private TradeExecution buildTrade(Order buyOrder, Order sellOrder, double executedPrice, double quantity) {
        return TradeExecution.builder()
                .tradeId("TRD-" + UUID.randomUUID())
                .buyOrderId(buyOrder.getOrderId())
                .sellOrderId(sellOrder.getOrderId())
                .buyerId(buyOrder.getUserId())
                .sellerId(sellOrder.getUserId())
                .symbol(buyOrder.getSymbol())
                .quantity(quantity)
                .executedPrice(executedPrice)
                .executedAt(Instant.now())
                .build();
    }
}
