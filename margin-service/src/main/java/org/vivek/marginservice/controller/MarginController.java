package org.vivek.marginservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.vivek.marginservice.service.MarginServiceImpl;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/margin")
@RequiredArgsConstructor
public class MarginController {

    private final MarginServiceImpl marginService;

    @GetMapping("/{userId}")
    public ResponseEntity<MarginServiceImpl.MarginSnapshot> getMargin(@PathVariable String userId) {
        return ResponseEntity.ok(marginService.getMarginSnapshot(userId));
    }

    @PutMapping("/{userId}/deposit")
    public ResponseEntity<?> deposit(
            @PathVariable String userId,
            @RequestParam double amount) {
        try {
            return ResponseEntity.ok(marginService.deposit(userId, amount));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{userId}/withdraw")
    public ResponseEntity<?> withdraw(
            @PathVariable String userId,
            @RequestParam double amount) {
        try {
            return ResponseEntity.ok(marginService.withdraw(userId, amount));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
