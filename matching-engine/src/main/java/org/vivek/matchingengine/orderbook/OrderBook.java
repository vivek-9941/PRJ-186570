package org.vivek.matchingengine.orderbook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.TradeExecution;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

@Component
@Slf4j
public class OrderBook {

    // Descending: highest bid first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buyOrders =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    // Ascending: lowest ask first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> sellOrders =
            new ConcurrentSkipListMap<>();

    public synchronized Optional<TradeExecution> match(Order incomingOrder) {
        if (incomingOrder.getSide() == OrderSide.BUY) {
            return matchBuyOrder(incomingOrder);
        } else {
            return matchSellOrder(incomingOrder);
        }
    }

    private Optional<TradeExecution> matchBuyOrder(Order buyOrder) {
        if (sellOrders.isEmpty()) {
            addToBook(buyOrder);
            return Optional.empty();
        }

        double lowestAsk = sellOrders.firstKey();
        // BUY order at price X: match if top sell price <= X
        if (lowestAsk <= buyOrder.getPrice()) {
            ConcurrentLinkedQueue<Order> queue = sellOrders.get(lowestAsk);
            Order matchedSell = queue.poll();

            if (queue.isEmpty()) {
                sellOrders.remove(lowestAsk);
            }

            if (matchedSell == null) {
                addToBook(buyOrder);
                return Optional.empty();
            }

            TradeExecution trade = buildTrade(buyOrder, matchedSell, lowestAsk);
            log.info("Matched BUY {} with SELL {} at price {} for symbol {}",
                    buyOrder.getOrderId(), matchedSell.getOrderId(), lowestAsk, buyOrder.getSymbol());
            return Optional.of(trade);
        }

        addToBook(buyOrder);
        return Optional.empty();
    }

    private Optional<TradeExecution> matchSellOrder(Order sellOrder) {
        if (buyOrders.isEmpty()) {
            addToBook(sellOrder);
            return Optional.empty();
        }

        double highestBid = buyOrders.firstKey();
        // SELL order at price X: match if top buy price >= X
        if (highestBid >= sellOrder.getPrice()) {
            ConcurrentLinkedQueue<Order> queue = buyOrders.get(highestBid);
            Order matchedBuy = queue.poll();

            if (queue.isEmpty()) {
                buyOrders.remove(highestBid);
            }

            if (matchedBuy == null) {
                addToBook(sellOrder);
                return Optional.empty();
            }

            TradeExecution trade = buildTrade(matchedBuy, sellOrder, highestBid);
            log.info("Matched SELL {} with BUY {} at price {} for symbol {}",
                    sellOrder.getOrderId(), matchedBuy.getOrderId(), highestBid, sellOrder.getSymbol());
            return Optional.of(trade);
        }

        addToBook(sellOrder);
        return Optional.empty();
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

    private TradeExecution buildTrade(Order buyOrder, Order sellOrder, double executedPrice) {
        return TradeExecution.builder()
                .tradeId("TRD-" + UUID.randomUUID())
                .buyOrderId(buyOrder.getOrderId())
                .sellOrderId(sellOrder.getOrderId())
                .symbol(buyOrder.getSymbol())
                .quantity(Math.min(buyOrder.getQuantity(), sellOrder.getQuantity()))
                .executedPrice(executedPrice)
                .executedAt(Instant.now())
                .build();
    }
}
