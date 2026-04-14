package org.vivek.marketdataservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceTick {
    private String symbol;
    private double price;
    private double change;
    private double changePercent;
    private Instant timestamp;
    private int volume;
    private double vwap;
}
