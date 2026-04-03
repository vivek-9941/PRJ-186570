package org.vivek.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.order.dto.PlaceOrderRequest;
import org.vivek.order.repository.OrderRepository;
import org.vivek.order.service.OrderService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        Order order = Order.builder()
                .orderId(orderService.generateOrderId())
                .userId(request.getUserId())
                .symbol(request.getSymbol())
                .side(request.getSide())
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
}
