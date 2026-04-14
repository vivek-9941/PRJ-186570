package org.vivek.ledgerservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerConsumer ledgerConsumer;

    public LedgerController(LedgerConsumer ledgerConsumer) {
        this.ledgerConsumer = ledgerConsumer;
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getLedger(@PathVariable String userId) {
        return ledgerConsumer.getBalance(userId);
    }

    @GetMapping("/{userId}/holdings")
    public Map<String, Double> getHoldings(@PathVariable String userId) {
        return ledgerConsumer.getHoldings(userId);
    }

    @GetMapping("/{userId}/history")
    public List<LedgerEntry> getHistory(@PathVariable String userId) {
        return ledgerConsumer.getHistory(userId);
    }
}
