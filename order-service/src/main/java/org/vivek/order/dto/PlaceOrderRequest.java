package org.vivek.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.vivek.commonmodule.model.OrderSide;

@Data
public class PlaceOrderRequest {
    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "symbol is required")
    private String symbol;

    @NotNull(message = "side is required")
    private OrderSide side;

    @DecimalMin(value = "0.000001", message = "quantity must be greater than 0")
    private double quantity;

    @DecimalMin(value = "0.000001", message = "price must be greater than 0")
    private double price;
}
