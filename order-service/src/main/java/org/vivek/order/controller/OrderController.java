package org.vivek.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;
import org.vivek.order.client.MatchingEngineClient;
import org.vivek.order.dto.PlaceOrderRequest;
import org.vivek.order.repository.OrderRepository;
import org.vivek.order.service.OrderService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final MatchingEngineClient matchingEngineClient;

    @PostMapping
    public ResponseEntity<Map<String, Object>> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderType orderType = request.getOrderType() != null ? request.getOrderType() : OrderType.LIMIT;
        LocalDateTime expiryTime = resolveExpiryTime(orderType, request.getExpiryTime());

        Order order = Order.builder()
                .orderId(orderService.generateOrderId())
                .userId(request.getUserId())
                .symbol(request.getSymbol())
                .side(request.getSide())
                .orderType(orderType)
                .expiryTime(expiryTime)
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        orderRepository.save(order);

        // Immediately trigger async processing
        CompletableFuture.runAsync(() -> orderService.processOrder(order));

        return ResponseEntity.accepted().body(Map.of(
                "orderId", order.getOrderId(),
                "status", "PENDING",
                "message", "Order received"
        ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        Order order = orderRepository.findById(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUser(@PathVariable String userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return ResponseEntity.ok(orders);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable String orderId) {
        Order order = orderRepository.findById(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PENDING && status != OrderStatus.PARTIALLY_FILLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot cancel order in status " + status));
        }

        matchingEngineClient.cancel(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "orderId", order.getOrderId(),
                "status", order.getStatus().name(),
                "message", "Order cancelled"
        ));
    }

    private LocalDateTime resolveExpiryTime(OrderType orderType, LocalDateTime requestedExpiryTime) {
        if (orderType != OrderType.GTD) {
            return requestedExpiryTime;
        }

        if (requestedExpiryTime != null) {
            return requestedExpiryTime;
        }

        return LocalDate.now().atTime(17, 0);
    }
}
