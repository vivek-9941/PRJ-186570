package org.vivek.matchingengine.orderbook;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.matchingengine.config.KafkaProducerConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class SymbolOrderBook {

    private static final double EPSILON = 1e-9;
    private static final int DEFAULT_DEPTH_LEVELS = 5;

    @Getter
    private final String symbol;

    // Descending: highest bid first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buyOrders =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    // Ascending: lowest ask first
    private final ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> sellOrders =
            new ConcurrentSkipListMap<>();

    private final KafkaTemplate<String, CancellationEvent> cancellationKafkaTemplate;

    public SymbolOrderBook(String symbol) {
        this(symbol, null);
    }

    public SymbolOrderBook(String symbol, KafkaTemplate<String, CancellationEvent> cancellationKafkaTemplate) {
        this.symbol = symbol;
        this.cancellationKafkaTemplate = cancellationKafkaTemplate;
    }

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

    public synchronized boolean cancel(String orderId) {
        return cancelOrder(orderId) != null;
    }

    public synchronized Order cancelOrder(String orderId) {
        Order cancelledBuy = cancelFromBook(buyOrders, orderId);
        if (cancelledBuy != null) {
            return cancelledBuy;
        }
        return cancelFromBook(sellOrders, orderId);
    }

    public synchronized BookSnapshot snapshot() {
        Double bestBid = buyOrders.isEmpty() ? null : buyOrders.firstKey();
        Double bestAsk = sellOrders.isEmpty() ? null : sellOrders.firstKey();

        return BookSnapshot.builder()
                .symbol(symbol)
                .bestBid(bestBid)
                .bestAsk(bestAsk)
                .spread(spread(bestBid, bestAsk))
                .buyLevels(extractLevels(buyOrders, DEFAULT_DEPTH_LEVELS))
                .sellLevels(extractLevels(sellOrders, DEFAULT_DEPTH_LEVELS))
                .totalBuyQty(totalQty(buyOrders))
                .totalSellQty(totalQty(sellOrders))
                .build();
    }

    public synchronized OrderBookDepth depth() {
        Double bestBid = buyOrders.isEmpty() ? null : buyOrders.firstKey();
        Double bestAsk = sellOrders.isEmpty() ? null : sellOrders.firstKey();

        return OrderBookDepth.builder()
                .symbol(symbol)
                .bids(extractLevels(buyOrders, DEFAULT_DEPTH_LEVELS))
                .asks(extractLevels(sellOrders, DEFAULT_DEPTH_LEVELS))
                .spread(spread(bestBid, bestAsk))
                .midPrice(midPrice(bestBid, bestAsk))
                .build();
    }

    public synchronized void addRestingOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order is required");
        }
        if (order.getSide() == null) {
            throw new IllegalArgumentException("Order side is required");
        }
        if (order.getQuantity() <= EPSILON) {
            throw new IllegalArgumentException("Order quantity must be greater than 0");
        }
        if (!symbol.equalsIgnoreCase(order.getSymbol())) {
            throw new IllegalArgumentException("Order symbol does not match book symbol");
        }

        if (order.getOrderType() == null) {
            order.setOrderType(OrderType.LIMIT);
        }
        if (order.getStatus() == null) {
            order.setStatus(OrderStatus.PENDING);
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(Instant.now());
        }
        order.setUpdatedAt(Instant.now());

        addToBook(order);
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

    private Order cancelFromBook(ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> bookSide, String orderId) {
        // Production systems maintain a separate HashMap<orderId, Order> for O(1) cancel lookup - omitted here for simplicity.
        for (Double priceLevel : new ArrayList<>(bookSide.keySet())) {
            ConcurrentLinkedQueue<Order> queue = bookSide.get(priceLevel);
            if (queue == null) {
                continue;
            }

            for (Order order : queue) {
                if (!orderId.equals(order.getOrderId())) {
                    continue;
                }

                boolean removed = queue.remove(order);
                if (!removed) {
                    return null;
                }

                if (queue.isEmpty()) {
                    bookSide.remove(priceLevel);
                }

                order.setStatus(OrderStatus.CANCELLED);
                order.setUpdatedAt(Instant.now());
                return order;
            }
        }
        return null;
    }

    private void applyIncomingOrderState(Order incomingOrder, double remainingQty, List<TradeExecution> executions) {
        Instant updatedAt = Instant.now();
        boolean matched = !executions.isEmpty();
        OrderType orderType = incomingOrder.getOrderType() != null ? incomingOrder.getOrderType() : OrderType.LIMIT;

        if (remainingQty > EPSILON) {
            if (orderType == OrderType.IOC) {
                incomingOrder.setQuantity(remainingQty);
                incomingOrder.setStatus(matched ? OrderStatus.PARTIALLY_FILLED : OrderStatus.CANCELLED);
                incomingOrder.setUpdatedAt(updatedAt);
                publishCancellationEvent(incomingOrder, updatedAt);
                log.info("IOC order {} expired: {} unfilled", incomingOrder.getOrderId(), remainingQty);
                return;
            }

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

    private void publishCancellationEvent(Order order, Instant cancelledAt) {
        if (cancellationKafkaTemplate == null) {
            return;
        }

        CancellationEvent event = CancellationEvent.builder()
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .symbol(order.getSymbol())
                .cancelledAt(cancelledAt)
                .build();

        cancellationKafkaTemplate.send(
                KafkaProducerConfig.TOPIC_ORDER_CANCELLED,
                order.getOrderId(),
                event
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish IOC cancellation for order {}: {}", order.getOrderId(), ex.getMessage());
            }
        });
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

    private Double spread(Double bestBid, Double bestAsk) {
        if (bestBid == null || bestAsk == null) {
            return null;
        }
        return bestAsk - bestBid;
    }

    private Double midPrice(Double bestBid, Double bestAsk) {
        if (bestBid == null || bestAsk == null) {
            return null;
        }
        return (bestBid + bestAsk) / 2.0d;
    }

    private double totalQty(ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> side) {
        return side.values().stream()
                .flatMap(queue -> queue.stream())
                .mapToDouble(Order::getQuantity)
                .sum();
    }

    private List<PriceLevel> extractLevels(ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> side, int levels) {
        List<PriceLevel> result = new ArrayList<>();
        for (Double price : side.keySet()) {
            if (result.size() >= levels) {
                break;
            }
            ConcurrentLinkedQueue<Order> queue = side.get(price);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            double quantity = queue.stream().mapToDouble(Order::getQuantity).sum();
            result.add(PriceLevel.builder()
                    .price(price)
                    .quantity(quantity)
                    .orderCount(queue.size())
                    .build());
        }
        return result;
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
