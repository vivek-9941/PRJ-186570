package org.vivek.order.service;

import com.trade.orderservice.dag.DAGExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.DAGResult;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.order.client.MatchingEngineClient;
import org.vivek.order.client.MatchingEngineResponse;
import org.vivek.order.repository.OrderRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final double EPSILON = 1e-9;

    private final OrderRepository orderRepository;
    private final DAGExecutor dagExecutor;
    private final MatchingEngineClient matchingEngineClient;

    public String generateOrderId() {
        return "ORD-" + UUID.randomUUID().toString();
    }

    public void processOrder(Order order) {
        // Update status -> VALIDATING, save
        updateStatus(order, OrderStatus.VALIDATING);
        orderRepository.save(order);

        // Call dagExecutor.execute(order)
        CompletableFuture<DAGResult> dagFuture = dagExecutor.execute(order);

        dagFuture.whenComplete((dagResult, throwable) -> {
            if (throwable != null) {
                log.error("DAG execution failed exceptionally for order {}", order.getOrderId(), throwable);
                updateStatus(order, OrderStatus.FAILED);
                orderRepository.save(order);
                return;
            }

            try {
                if (dagResult.isAllPassed()) {
                    // On DAGResult.allPassed=true: status -> APPROVED, then call matchingEngineClient.route(order)
                    updateStatus(order, OrderStatus.APPROVED);
                    orderRepository.save(order);

                    updateStatus(order, OrderStatus.ROUTED);
                    orderRepository.save(order);

                    MatchingEngineResponse matchingResponse = matchingEngineClient.route(order);
                    if (matchingResponse == null) {
                        // Handle connection errors gracefully (log + mark order FAILED)
                        updateStatus(order, OrderStatus.FAILED);
                        orderRepository.save(order);
                    } else {
                        applyMatchingOutcome(order, matchingResponse);
                        orderRepository.save(order);
                    }
                } else {
                    // On DAGResult.allPassed=false: status -> REJECTED, save rejection reason
                    updateStatus(order, OrderStatus.REJECTED);
                    log.info("Order {} rejected. Reason: {}", order.getOrderId(), dagResult.getFinalReason());
                    orderRepository.save(order);
                }
            } catch (Exception ex) {
                log.error("Unexpected error processing DAG result for order {}", order.getOrderId(), ex);
                updateStatus(order, OrderStatus.FAILED);
                orderRepository.save(order);
            }
        });
    }

    private void updateStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        order.setUpdatedAt(Instant.now());
        log.info("Order {} status transitioned: [{}] -> [{}] at {}", 
                order.getOrderId(), 
                oldStatus != null ? oldStatus : "NEW", 
                newStatus, 
                order.getUpdatedAt());
    }

    private void applyMatchingOutcome(Order order, MatchingEngineResponse matchingResponse) {
        double remainingQty = matchingResponse.getRemainingQty();
        double totalFilled = matchingResponse.getTotalFilled();
        order.setQuantity(remainingQty <= EPSILON ? 0.0d : remainingQty);

        if (remainingQty <= EPSILON && totalFilled > EPSILON) {
            updateStatus(order, OrderStatus.EXECUTED);
            return;
        }

        if (totalFilled > EPSILON) {
            updateStatus(order, OrderStatus.PARTIALLY_FILLED);
            return;
        }

        updateStatus(order, OrderStatus.PENDING);
    }
}
