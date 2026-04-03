package org.vivek.commonmodule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private OrderStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
