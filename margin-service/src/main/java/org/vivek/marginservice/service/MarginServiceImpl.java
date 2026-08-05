package org.vivek.marginservice.service;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.vivek.commonmodule.model.CancellationEvent;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.marginservice.entity.MarginAccount;
import org.vivek.marginservice.entity.OrderReservation;
import org.vivek.marginservice.repository.MarginAccountRepository;
import org.vivek.marginservice.repository.OrderReservationRepository;
import org.vivek.trade.margin.grpc.MarginServiceGrpc;
import org.vivek.trade.margin.grpc.ValidationRequest;
import org.vivek.trade.margin.grpc.ValidationResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

@GrpcService
@Slf4j
public class MarginServiceImpl extends MarginServiceGrpc.MarginServiceImplBase {

    private static final double DELIVERY_MARGIN_RATE = 1.0d;
    private static final double INTRADAY_MARGIN_RATE = 0.2d;

    @Autowired
    private MarginAccountRepository marginAccountRepository;

    @Autowired
    private OrderReservationRepository orderReservationRepository;

    @Value("${grpc.server.port:9094}")
    private int port;

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
        MarginAccount account = marginAccountRepository.findById(userId)
                .orElse(new MarginAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));

        double cashBalance = account.getCashBalance().doubleValue();
        double holdingsValue = account.getHoldingsValue().doubleValue();
        double reserved = account.getReservedMargin().doubleValue();
        double available = cashBalance + holdingsValue - reserved;
        double totalNetworth = cashBalance + holdingsValue;
        return new MarginSnapshot(cashBalance, holdingsValue, reserved, available, totalNetworth);
    }

    @Transactional
    public synchronized MarginSnapshot deposit(String userId, double amount) {
        if (amount <= 0.0d) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }
        MarginAccount account = marginAccountRepository.findById(userId)
                .orElse(new MarginAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));
        account.setCashBalance(account.getCashBalance().add(BigDecimal.valueOf(amount)));
        marginAccountRepository.save(account);
        log.info("Deposited {} into margin account for user {}", inr(amount), userId);
        return getMarginSnapshot(userId);
    }

    @Transactional
    public synchronized MarginSnapshot withdraw(String userId, double amount) {
        if (amount <= 0.0d) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }

        MarginAccount account = marginAccountRepository.findById(userId)
                .orElse(new MarginAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));
        double cashBalance = account.getCashBalance().doubleValue();
        if (amount > cashBalance) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "INSUFFICIENT_FUNDS: available cash %s, requested %s",
                    inr(cashBalance), inr(amount)));
        }

        account.setCashBalance(account.getCashBalance().subtract(BigDecimal.valueOf(amount)));
        marginAccountRepository.save(account);
        log.info("Withdrew {} from margin account for user {}", inr(amount), userId);
        return getMarginSnapshot(userId);
    }

    @KafkaListener(
            topics = "trade-executed",
            groupId = "margin-group",
            properties = {
                    "spring.json.value.default.type=org.vivek.commonmodule.model.TradeExecution"
            }
    )
    @Transactional
    public void onTradeExecuted(TradeExecution trade) {
        synchronized (this) {
            releaseReservation(trade.getBuyOrderId());
            releaseReservation(trade.getSellOrderId());

            double tradeValue = trade.getExecutedPrice() * trade.getQuantity();
            if (trade.getBuyerId() != null && !trade.getBuyerId().isBlank()) {
                marginAccountRepository.findById(trade.getBuyerId()).ifPresent(account -> {
                    account.setCashBalance(
                            account.getCashBalance().subtract(BigDecimal.valueOf(
                                    trade.getExecutedPrice() * trade.getQuantity())));
                    marginAccountRepository.save(account);
                });
                log.info("Trade settled BUY side: userId={} debited {}", trade.getBuyerId(), inr(tradeValue));
            }
            if (trade.getSellerId() != null && !trade.getSellerId().isBlank()) {
                marginAccountRepository.findById(trade.getSellerId()).ifPresent(account -> {
                    account.setCashBalance(
                            account.getCashBalance().add(BigDecimal.valueOf(
                                    trade.getExecutedPrice() * trade.getQuantity())));
                    marginAccountRepository.save(account);
                });
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
    @Transactional
    public void onOrderCancelled(CancellationEvent event) {
        synchronized (this) {
            releaseReservation(event.getOrderId());
            log.info("Margin released for cancelled order {}", event.getOrderId());
        }
    }

    // synchronized + @Transactional: synchronized prevents JVM-level race condition,
    // @Transactional provides database atomicity and rollback
    @Transactional
    private synchronized ValidationOutcome validateAndReserve(ValidationRequest request) {
        String userId = request.getUserId();
        String sideValue = request.getSide();
        OrderSide side = parseSide(sideValue);
        OrderType orderType = parseOrderType(request.getOrderType());
        double marginRate = resolveMarginRate(orderType);

        double grossRequiredMargin = request.getQuantity() * request.getPrice() * marginRate;
        double requiredMargin = shouldRequireMargin(side, orderType) ? grossRequiredMargin : 0.0d;

        MarginAccount account = marginAccountRepository
                .findById(userId)
                .orElse(new MarginAccount(userId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()));

        double cashBalance = account.getCashBalance().doubleValue();
        double holdingsValue = account.getHoldingsValue().doubleValue();
        double reserved = account.getReservedMargin().doubleValue();
        double available = cashBalance + holdingsValue - reserved;

        if (side == OrderSide.BUY && requiredMargin > available) {
            log.debug("Margin check userId={} required={} available={} result={}",
                    userId, inr(requiredMargin), inr(available), "FAIL");
            return new ValidationOutcome(false, String.format(Locale.US,
                    "INSUFFICIENT_MARGIN: required %s, available %s, cash %s, holdings %s, reserved %s",
                    inr(requiredMargin), inr(available), inr(cashBalance), inr(holdingsValue), inr(reserved)));
        }

        if (side == OrderSide.SELL && orderType == OrderType.IOC && requiredMargin > available) {
            log.debug("Margin check userId={} required={} available={} result={}",
                    userId, inr(requiredMargin), inr(available), "FAIL");
            return new ValidationOutcome(false, String.format(Locale.US,
                    "INSUFFICIENT_MARGIN_FOR_SHORT: intraday short requires %s, available %s",
                    inr(requiredMargin), inr(available)));
        }

        // Update reserved margin in DB — atomic with @Transactional
        account.setReservedMargin(
                account.getReservedMargin().add(BigDecimal.valueOf(requiredMargin)));
        marginAccountRepository.save(account);

        // Record per-order reservation for later release
        orderReservationRepository.save(
                new OrderReservation(request.getOrderId(),
                        userId, BigDecimal.valueOf(requiredMargin), Instant.now()));

        double updatedReserved = account.getReservedMargin().doubleValue();
        log.debug("Margin check userId={} required={} available={} result={}",
                userId, inr(requiredMargin), inr(available), "PASS");

        return new ValidationOutcome(true, String.format(Locale.US,
                "MARGIN_OK: required=%s available=%s reserved=%s",
                inr(requiredMargin), inr(available), inr(updatedReserved)));
    }

    private void releaseReservation(String orderId) {
        if (orderId == null || orderId.isBlank()) return;
        orderReservationRepository.findByOrderId(orderId).ifPresent(reservation -> {
            marginAccountRepository.findById(reservation.getUserId()).ifPresent(account -> {
                account.setReservedMargin(
                        account.getReservedMargin()
                                .subtract(reservation.getAmount())
                                .max(BigDecimal.ZERO));
                marginAccountRepository.save(account);
            });
            orderReservationRepository.deleteByOrderId(orderId);
        });
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

    private record ValidationOutcome(boolean success, String reason) {
    }
}
