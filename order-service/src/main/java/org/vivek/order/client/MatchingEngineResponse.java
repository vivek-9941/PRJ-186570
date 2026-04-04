package org.vivek.order.client;

import lombok.Data;

@Data
public class MatchingEngineResponse {
    private boolean matched;
    private int fillCount;
    private double totalFilled;
    private double remainingQty;
}
