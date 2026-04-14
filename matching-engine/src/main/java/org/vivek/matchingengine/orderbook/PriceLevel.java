package org.vivek.matchingengine.orderbook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceLevel {
    private double price;
    private double quantity;
    private int orderCount;
}
