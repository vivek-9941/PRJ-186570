package org.vivek.complianceservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.vivek.complianceservice.service.ComplianceServiceImpl;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceServiceImpl complianceService;

    @GetMapping("/bands")
    public ResponseEntity<Map<String, ComplianceServiceImpl.BandSnapshot>> getBands() {
        return ResponseEntity.ok(complianceService.getBands());
    }

    @GetMapping("/banned")
    public ResponseEntity<Set<String>> getBannedSymbols() {
        return ResponseEntity.ok(complianceService.getBannedSymbols());
    }

    @PostMapping("/banned/{symbol}")
    public ResponseEntity<Map<String, Object>> addBannedSymbol(@PathVariable String symbol) {
        complianceService.addBannedSymbol(symbol);
        return ResponseEntity.ok(Map.of(
                "symbol", symbol.toUpperCase(),
                "status", "banned"
        ));
    }

    @DeleteMapping("/banned/{symbol}")
    public ResponseEntity<Map<String, Object>> removeBannedSymbol(@PathVariable String symbol) {
        complianceService.removeBannedSymbol(symbol);
        return ResponseEntity.ok(Map.of(
                "symbol", symbol.toUpperCase(),
                "status", "active"
        ));
    }
}
