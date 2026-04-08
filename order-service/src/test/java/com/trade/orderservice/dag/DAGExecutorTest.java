package com.trade.orderservice.dag;

import com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.vivek.commonmodule.model.DAGResult;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.OrderSide;
import org.vivek.commonmodule.model.OrderStatus;
import org.vivek.commonmodule.model.TaskResult;
import org.vivek.trade.compliance.grpc.ComplianceServiceGrpc;
import org.vivek.trade.margin.grpc.MarginServiceGrpc;
import org.vivek.trade.risk.grpc.RiskServiceGrpc;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DAGExecutorTest {

    @Mock
    private RiskServiceGrpc.RiskServiceFutureStub riskStub;

    @Mock
    private MarginServiceGrpc.MarginServiceFutureStub marginStub;

    @Mock
    private ComplianceServiceGrpc.ComplianceServiceFutureStub complianceStub;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DAGExecutor dagExecutor;
    private DAGExecutor selfDelegate;
    private ScheduledExecutorService delayedResponseScheduler;

    private final Order sampleOrder = Order.builder()
            .orderId("ORD-001")
            .userId("U1")
            .symbol("INFY")
            .side(OrderSide.BUY)
            .quantity(10.0d)
            .price(1800.0d)
            .status(OrderStatus.PENDING)
            .build();

    @BeforeEach
    void setUp() {
        selfDelegate = new DAGExecutor(riskStub, marginStub, complianceStub, eventPublisher, null);
        dagExecutor = new DAGExecutor(riskStub, marginStub, complianceStub, eventPublisher, selfDelegate);
        delayedResponseScheduler = Executors.newScheduledThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        shutdownExecutors(dagExecutor);
        shutdownExecutors(selfDelegate);
        delayedResponseScheduler.shutdownNow();
    }

    @Test
    void all_checks_pass_returns_approved_result() throws Exception {
        when(riskStub.validate(any(org.vivek.trade.risk.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(riskResponse(true, "OK", 20L)));
        when(marginStub.validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(marginResponse(true, "OK", 25L)));
        when(complianceStub.validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(complianceResponse(true, "OK", 30L)));

        DAGResult result = dagExecutor.execute(sampleOrder).get(3, TimeUnit.SECONDS);

        assertTrue(result.isAllPassed());
        assertEquals(3, result.getTaskResults().size());
        assertTrue(result.getTaskResults().stream().allMatch(TaskResult::isSuccess));
    }

    @Test
    void one_check_fails_returns_rejected_result() throws Exception {
        when(riskStub.validate(any(org.vivek.trade.risk.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(riskResponse(false, "MAX_LOSS_EXCEEDED", 20L)));
        when(marginStub.validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(marginResponse(true, "OK", 25L)));
        when(complianceStub.validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(complianceResponse(true, "OK", 30L)));

        DAGResult result = dagExecutor.execute(sampleOrder).get(3, TimeUnit.SECONDS);

        assertFalse(result.isAllPassed());
        assertEquals("MAX_LOSS_EXCEEDED", result.getFinalReason());
    }

    @Test
    void all_three_called_even_when_one_fails() throws Exception {
        when(riskStub.validate(any(org.vivek.trade.risk.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(riskResponse(false, "MAX_LOSS_EXCEEDED", 20L)));
        when(marginStub.validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(marginResponse(true, "OK", 25L)));
        when(complianceStub.validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(complianceResponse(true, "OK", 30L)));

        dagExecutor.execute(sampleOrder).get(3, TimeUnit.SECONDS);

        verify(marginStub, atLeastOnce()).validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class));
        verify(complianceStub, atLeastOnce()).validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class));
    }

    @Test
    void timeout_triggers_retry_then_failure() throws Exception {
        when(riskStub.validate(any(org.vivek.trade.risk.grpc.ValidationRequest.class)))
                .thenAnswer(invocation -> delayedFuture(riskResponse(true, "OK", 10L), 600L));
        when(marginStub.validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(marginResponse(true, "OK", 25L)));
        when(complianceStub.validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class)))
                .thenReturn(immediateFuture(complianceResponse(true, "OK", 30L)));

        long start = System.currentTimeMillis();
        DAGResult result = dagExecutor.execute(sampleOrder).get(3, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isAllPassed());
        String reason = String.valueOf(result.getFinalReason());
        assertTrue(reason.toUpperCase().contains("TIMEOUT") || reason.contains("CIRCUIT_OPEN"));
        assertTrue(elapsed < 2000L);
    }

    @Test
    void execution_completes_within_time_budget() throws Exception {
        when(riskStub.validate(any(org.vivek.trade.risk.grpc.ValidationRequest.class)))
                .thenAnswer(invocation -> delayedFuture(riskResponse(true, "OK", 150L), 150L));
        when(marginStub.validate(any(org.vivek.trade.margin.grpc.ValidationRequest.class)))
                .thenAnswer(invocation -> delayedFuture(marginResponse(true, "OK", 150L), 150L));
        when(complianceStub.validate(any(org.vivek.trade.compliance.grpc.ValidationRequest.class)))
                .thenAnswer(invocation -> delayedFuture(complianceResponse(true, "OK", 150L), 150L));

        long start = System.currentTimeMillis();
        DAGResult result = dagExecutor.execute(sampleOrder).get(3, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isAllPassed());
        assertTrue(elapsed < 300L);
    }

    private void shutdownExecutors(DAGExecutor executor) {
        if (executor == null) {
            return;
        }
        shutdownFieldExecutor(executor, "executorService");
        shutdownFieldExecutor(executor, "scheduledExecutor");
    }

    private void shutdownFieldExecutor(DAGExecutor executor, String fieldName) {
        try {
            Field field = DAGExecutor.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(executor);
            if (value instanceof ExecutorService service) {
                service.shutdownNow();
            }
            if (value instanceof ScheduledExecutorService service) {
                service.shutdownNow();
            }
        } catch (Exception ignored) {
            // Best effort cleanup for thread pools created by DAGExecutor.
        }
    }

    private <T> SettableFuture<T> immediateFuture(T value) {
        SettableFuture<T> future = SettableFuture.create();
        future.set(value);
        return future;
    }

    private <T> SettableFuture<T> delayedFuture(T value, long delayMs) {
        SettableFuture<T> future = SettableFuture.create();
        delayedResponseScheduler.schedule(() -> future.set(value), delayMs, TimeUnit.MILLISECONDS);
        return future;
    }

    private org.vivek.trade.risk.grpc.ValidationResponse riskResponse(boolean success, String reason, long latencyMs) {
        return org.vivek.trade.risk.grpc.ValidationResponse.newBuilder()
                .setServiceId("risk-service")
                .setSuccess(success)
                .setReason(reason)
                .setLatencyMs(latencyMs)
                .build();
    }

    private org.vivek.trade.margin.grpc.ValidationResponse marginResponse(boolean success, String reason, long latencyMs) {
        return org.vivek.trade.margin.grpc.ValidationResponse.newBuilder()
                .setServiceId("margin-service")
                .setSuccess(success)
                .setReason(reason)
                .setLatencyMs(latencyMs)
                .build();
    }

    private org.vivek.trade.compliance.grpc.ValidationResponse complianceResponse(boolean success, String reason, long latencyMs) {
        return org.vivek.trade.compliance.grpc.ValidationResponse.newBuilder()
                .setServiceId("compliance-service")
                .setSuccess(success)
                .setReason(reason)
                .setLatencyMs(latencyMs)
                .build();
    }
}