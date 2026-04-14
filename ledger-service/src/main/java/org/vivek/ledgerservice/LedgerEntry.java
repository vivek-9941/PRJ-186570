package org.vivek.ledgerservice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {
    private String entryId;
    private String tradeId;
    private String userId;
    private String side;
    private String symbol;
    private double quantity;
    private double price;
    private double amount;
    private double balanceAfter;
    private Instant timestamp;
}
