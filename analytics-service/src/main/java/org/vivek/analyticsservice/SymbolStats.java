package org.vivek.analyticsservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SymbolStats {
    private long totalTrades;
    private double totalVolume;
    private double lastPrice;
    private double avgPrice;
}
