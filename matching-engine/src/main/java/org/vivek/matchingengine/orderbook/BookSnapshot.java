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
public class BookSnapshot {
    private String symbol;
    private Double bestBid;
    private Double bestAsk;
    private Double spread;
    private List<PriceLevel> buyLevels;
    private List<PriceLevel> sellLevels;
    private double totalBuyQty;
    private double totalSellQty;
}
