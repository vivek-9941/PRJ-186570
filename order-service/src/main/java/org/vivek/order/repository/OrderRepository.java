package org.vivek.order.repository;

import org.springframework.stereotype.Repository;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.OrderType;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class OrderRepository {

    private final ConcurrentHashMap<String, Order> orderStore = new ConcurrentHashMap<>();

    public Order save(Order order) {
        orderStore.put(order.getOrderId(), order);
        return order;
    }

    public Order findById(String orderId) {
        return orderStore.get(orderId);
    }

    public List<Order> findByUserId(String userId) {
        return orderStore.values().stream()
                .filter(order -> userId.equals(order.getUserId()))
                .collect(Collectors.toList());
    }

    public List<Order> findByStatusInAndOrderType(List<OrderStatus> statuses, OrderType orderType) {
        return orderStore.values().stream()
                .filter(order -> statuses.contains(order.getStatus()))
                .filter(order -> orderType == order.getOrderType())
                .collect(Collectors.toList());
    }
}
