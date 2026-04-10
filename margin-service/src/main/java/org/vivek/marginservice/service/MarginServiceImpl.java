package org.vivek.marginservice.service;

import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.trade.margin.grpc.MarginServiceGrpc;
import org.vivek.trade.margin.grpc.ValidationRequest;
import org.vivek.trade.margin.grpc.ValidationResponse;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@GrpcService
@Slf4j
public class MarginServiceImpl extends MarginServiceGrpc.MarginServiceImplBase {

    private static final double DELIVERY_MARGIN_RATE = 1.0d;
    private static final double INTRADAY_MARGIN_RATE = 0.2d;

    private final Map<String, Double> userCashBalance = new ConcurrentHashMap<>();
    private final Map<String, Double> reservedMargin = new ConcurrentHashMap<>();
    private final Map<String, Double> holdingsValue = new ConcurrentHashMap<>();
    private final Map<String, Reservation> orderReservations = new ConcurrentHashMap<>();

    @Value("${grpc.server.port:9094}")
    private int port;

    @PostConstruct
    public void init() {
        userCashBalance.clear();
        userCashBalance.put("U1", 100000.0d);
        userCashBalance.put("U2", 250000.0d);
        userCashBalance.put("U3", 50000.0d);

        reservedMargin.clear();
        reservedMargin.put("U1", 0.0d);
        reservedMargin.put("U2", 0.0d);
        reservedMargin.put("U3", 0.0d);

        holdingsValue.clear();
        holdingsValue.put("U1", 150000.0d);
        holdingsValue.put("U2", 80000.0d);
        holdingsValue.put("U3", 0.0d);

        orderReservations.clear();

        log.info("MarginService initialized on port {} with {} cash accounts", port, userCashBalance.size());
    }

    @Override
    public void validate(ValidationRequest request, StreamObserver<ValidationResponse> responseObserver) {
        long start = System.currentTimeMillis();

        try {
            ValidationOutcome outcome = validateAndReserve(request);

            responseObserver.onNext(ValidationResponse.newBuilder()
                    .setSuccess(outcome.success())
                    .setServiceId("margin-service")
                    .setReason(outcome.reason())
                    .setLatencyMs(System.currentTimeMillis() - start)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Margin validation failed unexpectedly for order {}", request.getOrderId(), ex);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Margin validation failed")
                    .withCause(ex)
                    .asRuntimeException());
        }
    }

    public synchronized MarginSnapshot getMarginSnapshot(String userId) {
        double cashBalance = userCashBalance.getOrDefault(userId, 0.0d);
        double collateral = holdingsValue.getOrDefault(userId, 0.0d);
        double reserved = reservedMargin.getOrDefault(userId, 0.0d);
        double available = cashBalance + collateral - reserved;
        double totalNetworth = cashBalance + collateral;
        return new MarginSnapshot(cashBalance, collateral, reserved, available, totalNetworth);
    }

    public synchronized MarginSnapshot deposit(String userId, double amount) {
        userCashBalance.merge(userId, amount, Double::sum);
        reservedMargin.putIfAbsent(userId, 0.0d);
        holdingsValue.putIfAbsent(userId, 0.0d);
        log.info("Deposited {} into margin account for user {}", inr(amount), userId);
        return getMarginSnapshot(userId);
    }

    @KafkaListener(
            topics = "trade-executed",
            groupId = "margin-group",
            properties = {
                    "spring.json.value.default.type=org.vivek.commonmodule.model.TradeExecution"
            }
    )
    public void onTradeExecuted(TradeExecution trade) {
        synchronized (this) {
            releaseReservedMargin(trade.getBuyOrderId(), "trade execution");
            releaseReservedMargin(trade.getSellOrderId(), "trade execution");

            double tradeValue = trade.getExecutedPrice() * trade.getQuantity();
            if (trade.getBuyerId() != null && !trade.getBuyerId().isBlank()) {
                userCashBalance.merge(trade.getBuyerId(), -tradeValue, Double::sum);
                log.info("Trade settled BUY side: userId={} debited {}", trade.getBuyerId(), inr(tradeValue));
            }
            if (trade.getSellerId() != null && !trade.getSellerId().isBlank()) {
                userCashBalance.merge(trade.getSellerId(), tradeValue, Double::sum);
                log.info("Trade settled SELL side: userId={} credited {}", trade.getSellerId(), inr(tradeValue));
            }
        }
    }

    @KafkaListener(
            topics = "order-cancelled",
            groupId = "margin-group",
            properties = {
                    "spring.json.value.default.type=org.vivek.commonmodule.model.CancellationEvent"
            }
    )
    public void onOrderCancelled(CancellationEvent event) {
        synchronized (this) {
            double released = releaseReservedMargin(event.getOrderId(), "cancellation");
            log.info("Margin released {} for cancelled order {}", inr(released), event.getOrderId());
        }
    }

    private synchronized ValidationOutcome validateAndReserve(ValidationRequest request) {
        String userId = request.getUserId();
        String sideValue = request.getSide();
        OrderSide side = parseSide(sideValue);
        OrderType orderType = parseOrderType(request.getOrderType());
        double marginRate = resolveMarginRate(orderType);

        double grossRequiredMargin = request.getQuantity() * request.getPrice() * marginRate;
        double requiredMargin = shouldRequireMargin(side, orderType) ? grossRequiredMargin : 0.0d;

        double cashBalance = userCashBalance.getOrDefault(userId, 0.0d);
        double collateral = holdingsValue.getOrDefault(userId, 0.0d);
        double reserved = reservedMargin.getOrDefault(userId, 0.0d);
        double available = cashBalance + collateral - reserved;

        if (side == OrderSide.BUY && requiredMargin > available) {
            log.debug("Margin check userId={} required={} available={} result={}",
                    userId, inr(requiredMargin), inr(available), "FAIL");
            return new ValidationOutcome(false, String.format(Locale.US,
                    "INSUFFICIENT_MARGIN: required %s, available %s, cash %s, holdings %s, reserved %s",
                    inr(requiredMargin), inr(available), inr(cashBalance), inr(collateral), inr(reserved)));
        }

        if (side == OrderSide.SELL && orderType == OrderType.IOC && requiredMargin > available) {
            log.debug("Margin check userId={} required={} available={} result={}",
                    userId, inr(requiredMargin), inr(available), "FAIL");
            return new ValidationOutcome(false, String.format(Locale.US,
                    "INSUFFICIENT_MARGIN_FOR_SHORT: intraday short requires %s, available %s",
                    inr(requiredMargin), inr(available)));
        }

        double updatedReserved = reservedMargin.merge(userId, requiredMargin, Double::sum);
        reservedMargin.putIfAbsent(userId, updatedReserved);
        orderReservations.put(request.getOrderId(), new Reservation(userId, requiredMargin));

        log.debug("Margin check userId={} required={} available={} result={}",
                userId, inr(requiredMargin), inr(available), "PASS");

        return new ValidationOutcome(true, String.format(Locale.US,
                "MARGIN_OK: required=%s available=%s reserved=%s",
                inr(requiredMargin), inr(available), inr(updatedReserved)));
    }

    private double resolveMarginRate(OrderType orderType) {
        return orderType == OrderType.IOC ? INTRADAY_MARGIN_RATE : DELIVERY_MARGIN_RATE;
    }

    private boolean shouldRequireMargin(OrderSide side, OrderType orderType) {
        return side == OrderSide.BUY || orderType == OrderType.IOC;
    }

    private OrderSide parseSide(String side) {
        try {
            return OrderSide.valueOf(side == null ? "BUY" : side.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown side {}, defaulting to BUY", side);
            return OrderSide.BUY;
        }
    }

    private OrderType parseOrderType(String orderType) {
        if (orderType == null || orderType.isBlank()) {
            return OrderType.LIMIT;
        }

        String normalized = orderType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IOC", "MIS", "INTRADAY" -> OrderType.IOC;
            case "GTD" -> OrderType.GTD;
            case "LIMIT", "CNC", "DELIVERY" -> OrderType.LIMIT;
            default -> {
                log.warn("Unknown orderType {}, defaulting to DELIVERY rules", orderType);
                yield OrderType.LIMIT;
            }
        };
    }
    
    private double releaseReservedMargin(String orderId, String reason) {
        if (orderId == null || orderId.isBlank()) {
            return 0.0d;
        }

        Reservation reservation = orderReservations.remove(orderId);
        if (reservation == null || reservation.amount() <= 0.0d) {
            return 0.0d;
        }

        reservedMargin.compute(reservation.userId(), (userId, current) -> {
            double existing = current == null ? 0.0d : current;
            return Math.max(0.0d, existing - reservation.amount());
        });
        log.info("Released {} reserved margin for order {} after {}", inr(reservation.amount()), orderId, reason);
        return reservation.amount();
    }

    private String inr(double amount) {
        return String.format(Locale.US, "₹%.2f", amount);
    }

    public record MarginSnapshot(
            double cashBalance,
            double holdingsValue,
            double reservedMargin,
            double availableMargin,
            double totalNetworth
    ) {
    }

    private record Reservation(String userId, double amount) {
    }

    private record ValidationOutcome(boolean success, String reason) {
    }
}
