package org.vivek.riskservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.vivek.riskservice.service.RiskServiceImpl;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskServiceImpl riskService;

    /**
     * GET /api/v1/risk/positions/{userId}
     * Returns all positions for the given user.
     */
    @GetMapping("/positions/{userId}")
    public ResponseEntity<Map<String, Double>> getPositions(@PathVariable String userId) {
        Map<String, Double> positions = riskService.getPositions(userId);
        return ResponseEntity.ok(positions);
    }

    /**
     * GET /api/v1/risk/config
     * Returns the 4 risk limit constants.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getRiskConfig() {
        return ResponseEntity.ok(riskService.getRiskConfig());
    }

    /**
     * PUT /api/v1/risk/positions/{userId}/{symbol}?quantity=X
     * Update position (simulates trade settlement updating positions).
     */
    @PutMapping("/positions/{userId}/{symbol}")
    public ResponseEntity<Map<String, Object>> updatePosition(
            @PathVariable String userId,
            @PathVariable String symbol,
            @RequestParam double quantity) {
        riskService.updatePosition(userId, symbol, quantity);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "symbol", symbol,
                "quantity", quantity,
                "status", "updated"
        ));
    }
}
