package org.vivek.matchingengine.orderbook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBookDepth {
    private String symbol;
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private Double spread;
    private Double midPrice;
}
