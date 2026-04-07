package org.vivek.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderType;

import java.time.LocalDateTime;

@Data
public class PlaceOrderRequest {
    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "symbol is required")
    private String symbol;

    @NotNull(message = "side is required")
    private OrderSide side;

    @NotNull(message = "orderType is required")
    private OrderType orderType;

    private LocalDateTime expiryTime;

    @DecimalMin(value = "0.000001", message = "quantity must be greater than 0")
    private double quantity;

    @DecimalMin(value = "0.000001", message = "price must be greater than 0")
    private double price;
}
