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
public class TradeExecution {
    private String tradeId;
    private String buyOrderId;
    private String sellOrderId;
    private String buyerId;
    private String sellerId;
    private String symbol;
    private double quantity;
    private double executedPrice;
    private Instant executedAt;
}
