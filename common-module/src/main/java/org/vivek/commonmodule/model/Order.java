package org.vivek.commonmodule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private String orderId;
    private String userId;
    private String symbol;
    private OrderSide side;
    private double quantity;
    private double price;
    @Builder.Default
    private OrderType orderType = OrderType.LIMIT;
    private LocalDateTime expiryTime;
    private OrderStatus status;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Order withQuantity(double quantity) {
        return Order.builder()
                .orderId(orderId)
                .userId(userId)
                .symbol(symbol)
                .side(side)
                .quantity(quantity)
                .price(price)
                .orderType(orderType)
                .expiryTime(expiryTime)
                .status(status)
                .rejectionReason(rejectionReason)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
