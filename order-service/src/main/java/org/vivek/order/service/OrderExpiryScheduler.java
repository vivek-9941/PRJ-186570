package org.vivek.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderExpiredEvent;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.order.client.MatchingEngineClient;
import org.vivek.order.config.OrderKafkaProducerConfig;
import org.vivek.order.repository.OrderRepository;

import java.time.DayOfWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExpiryScheduler {

    private static final List<OrderStatus> EXPIRABLE_STATUSES = List.of(
            OrderStatus.PENDING,
            OrderStatus.PARTIALLY_FILLED
    );

    private final OrderRepository orderRepository;
    private final MatchingEngineClient matchingEngineClient;
    private final KafkaTemplate<String, OrderExpiredEvent> orderExpiredKafkaTemplate;
    private final Clock clock;

    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void expireGTDOrders() {
        int expiredCount = expireOrders(true);
        log.info("End-of-day GTD expiry run complete. Expired {} orders", expiredCount);
    }

    @Scheduled(fixedRate = 60000)
    public void expireStaleOrders() {
        if (!isWithinTradingHours(LocalDateTime.now(clock))) {
            return;
        }

        int expiredCount = expireOrders(false);
        if (expiredCount > 0) {
            log.info("Stale GTD expiry run complete. Expired {} orders", expiredCount);
        }
    }

    private int expireOrders(boolean expireAllForEndOfDay) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Order> candidates = orderRepository.findByStatusInAndOrderType(EXPIRABLE_STATUSES, OrderType.GTD);
        int expiredCount = 0;

        for (Order order : candidates) {
            if (!shouldExpire(order, expireAllForEndOfDay, today, now)) {
                continue;
            }

            matchingEngineClient.cancel(order.getOrderId());

            order.setStatus(OrderStatus.EXPIRED);
            order.setUpdatedAt(Instant.now(clock));
            orderRepository.save(order);

            OrderExpiredEvent event = OrderExpiredEvent.builder()
                    .orderId(order.getOrderId())
                    .userId(order.getUserId())
                    .symbol(order.getSymbol())
                    .expiredAt(Instant.now(clock))
                    .build();

            orderExpiredKafkaTemplate.send(
                    OrderKafkaProducerConfig.TOPIC_ORDER_EXPIRED,
                    order.getOrderId(),
                    event
            );

            expiredCount++;
        }

        return expiredCount;
    }

    private boolean shouldExpire(Order order, boolean expireAllForEndOfDay, LocalDate today, LocalDateTime now) {
        if (expireAllForEndOfDay) {
            return true;
        }

        boolean pastExplicitExpiry = order.getExpiryTime() != null && !order.getExpiryTime().isAfter(now);
        return pastExplicitExpiry || isOrderOlderThanToday(order, today);
    }

    private boolean isOrderOlderThanToday(Order order, LocalDate today) {
        if (order.getCreatedAt() == null) {
            return false;
        }

        LocalDate createdDate = LocalDateTime.ofInstant(order.getCreatedAt(), clock.getZone()).toLocalDate();
        return createdDate.isBefore(today);
    }

    private boolean isWithinTradingHours(LocalDateTime timestamp) {
        DayOfWeek day = timestamp.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalTime time = timestamp.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(17, 0));
    }
}
