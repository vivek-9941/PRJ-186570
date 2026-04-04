package org.vivek.order.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.order.client.MatchingEngineClient;
import org.vivek.order.repository.OrderRepository;
import org.vivek.order.service.OrderService;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MatchingEngineClient matchingEngineClient;

    @Test
    void cancelOrderCancelsPendingOrder() {
        OrderController controller = new OrderController(orderService, orderRepository, matchingEngineClient);
        Order order = order("ORD-1", OrderStatus.PENDING);

        when(orderRepository.findById("ORD-1")).thenReturn(order);
        when(matchingEngineClient.cancel("ORD-1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.cancelOrder("ORD-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("CANCELLED", response.getBody().get("status"));
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertNotNull(order.getUpdatedAt());
        verify(orderRepository).save(order);
        verify(matchingEngineClient).cancel("ORD-1");
    }

    @Test
    void cancelOrderRejectsExecutedOrder() {
        OrderController controller = new OrderController(orderService, orderRepository, matchingEngineClient);
        Order order = order("ORD-2", OrderStatus.EXECUTED);

        when(orderRepository.findById("ORD-2")).thenReturn(order);

        ResponseEntity<Map<String, Object>> response = controller.cancelOrder("ORD-2");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Cannot cancel order in status EXECUTED", response.getBody().get("error"));
        verify(matchingEngineClient, never()).cancel("ORD-2");
        verify(orderRepository, never()).save(order);
    }

    private Order order(String orderId, OrderStatus status) {
        return Order.builder()
                .orderId(orderId)
                .userId("user-1")
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .quantity(2.0d)
                .price(100.0d)
                .status(status)
                .createdAt(Instant.now())
                .build();
    }
}
