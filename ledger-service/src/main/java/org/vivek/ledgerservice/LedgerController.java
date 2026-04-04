package org.vivek.ledgerservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
