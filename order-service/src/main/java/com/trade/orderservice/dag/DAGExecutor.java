package com.trade.orderservice.dag;

import com.google.common.util.concurrent.ListenableFuture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.vivek.commonmodule.model.DAGResult;
import org.vivek.commonmodule.model.Order;
import org.vivek.commonmodule.model.TaskResult;
import org.vivek.trade.compliance.grpc.ComplianceServiceGrpc;
import org.vivek.trade.margin.grpc.MarginServiceGrpc;
import org.vivek.trade.risk.grpc.RiskServiceGrpc;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
@Slf4j
public class DAGExecutor {

    private final RiskServiceGrpc.RiskServiceFutureStub riskStub;
    private final MarginServiceGrpc.MarginServiceFutureStub marginStub;
    private final ComplianceServiceGrpc.ComplianceServiceFutureStub complianceStub;
    private final ApplicationEventPublisher eventPublisher;
    private final DAGExecutor self;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutor;

    public DAGExecutor(RiskServiceGrpc.RiskServiceFutureStub riskStub,
                       MarginServiceGrpc.MarginServiceFutureStub marginStub,
                       ComplianceServiceGrpc.ComplianceServiceFutureStub complianceStub,
                       ApplicationEventPublisher eventPublisher,
                       @Lazy DAGExecutor self) {
        this.riskStub = riskStub;
        this.marginStub = marginStub;
        this.complianceStub = complianceStub;
        this.eventPublisher = eventPublisher;
        this.self = self;

        ThreadFactory threadFactory = new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "dag-worker-" + (++count));
            }
        };
        this.executorService = Executors.newFixedThreadPool(10, threadFactory);
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "dag-scheduler"));
    }

    @Timed("dag.execution")
    public CompletableFuture<DAGResult> execute(Order order) {
        long start  = System.nanoTime();

        log.info("Starting DAG execution for order: {}", order.getOrderId());

        CompletableFuture<TaskResult> riskTask = executeWithRetry("risk-service", order, () -> {
            return self.callRisk(order);
        });

        CompletableFuture<TaskResult> marginTask = executeWithRetry("margin-service", order, () -> {
            return self.callMargin(order);
        });

        CompletableFuture<TaskResult> complianceTask = executeWithRetry("compliance-service", order, () -> {
            return self.callCompliance(order);
        });

        return CompletableFuture.allOf(riskTask, marginTask, complianceTask).thenApply(v -> {
            List<TaskResult> results = Arrays.asList(riskTask.join(), marginTask.join(), complianceTask.join());
            long end  = System.nanoTime();
            double timeinms =  end - start;
            log.info("Finished DAG execution in time: {}", timeinms);
            log.info("All tasks completed for order: {}", order.getOrderId());
            return DAGResult.from(order.getOrderId(), results);
        });
    }

    private CompletableFuture<TaskResult> executeWithRetry(String serviceId, Order order, Supplier<CompletableFuture<TaskResult>> taskSupplier) {
        long start = System.currentTimeMillis();
        eventPublisher.publishEvent(new TaskStartedEvent(this, order.getOrderId(), serviceId));
        
        // As per requirements: "Fire all three gRPC calls simultaneously using CompletableFuture.supplyAsync() on a named thread pool"
        return CompletableFuture.supplyAsync(() -> retryAsync(taskSupplier, 2, 100), executorService)
                .thenCompose(fn -> fn)
                .handle((result, ex) -> {
                    long latencyMs = System.currentTimeMillis() - start;
                    if (ex != null) {
                        log.warn("Task failed for order {} on service {}: {}", order.getOrderId(), serviceId, ex.getMessage());
                        eventPublisher.publishEvent(new TaskCompletedEvent(this, order.getOrderId(), serviceId, false, latencyMs));
                        return TaskResult.builder()
                                .serviceId(serviceId)
                                .success(false)
                                .reason(ex.getMessage() != null ? ex.getMessage() : "Timeout or unknown error")
                                .latencyMs(latencyMs)
                                .build();
                    } else {
                        eventPublisher.publishEvent(new TaskCompletedEvent(this, order.getOrderId(), serviceId, result.isSuccess(), latencyMs));
                        return result;
                    }
                });
    }

    private CompletableFuture<TaskResult> retryAsync(Supplier<CompletableFuture<TaskResult>> supplier, int retriesLeft, long delayMs) {
        // Wrap each gRPC future with a 500ms timeout
        return supplier.get().orTimeout(500, TimeUnit.MILLISECONDS).handle((result, ex) -> {
            // Business-level validation failures (for example INSUFFICIENT_MARGIN) should not be retried.
            if (result != null && !result.isSuccess()) {
                if (result.getReason() != null && result.getReason().startsWith("CIRCUIT_OPEN")) {
                    return CompletableFuture.completedFuture(result);
                }
                return CompletableFuture.completedFuture(result);
            }

            if (ex != null) {
                if (retriesLeft > 0) {
                    log.warn("Retrying task due to failure/timeout, retries left: {}", retriesLeft);
                    CompletableFuture<TaskResult> retryFuture = new CompletableFuture<>();
                    scheduledExecutor.schedule(() -> {
                        retryAsync(supplier, retriesLeft - 1, delayMs)
                                .whenComplete((r, e) -> {
                                    if (e != null) retryFuture.completeExceptionally(e);
                                    else retryFuture.complete(r);
                                });
                    }, delayMs, TimeUnit.MILLISECONDS);
                    return retryFuture;
                } else {
                    log.error("Retry limit exceeded for task");
                    if (ex != null) throw new CompletionException(ex);
                    return CompletableFuture.completedFuture(result);
                }
            }
            return CompletableFuture.completedFuture(result);
        }).thenCompose(fn -> fn);
    }

    @CircuitBreaker(name = "riskService", fallbackMethod = "riskFallback")
    CompletableFuture<TaskResult> callRisk(Order order) {
        org.vivek.trade.risk.grpc.ValidationRequest request = org.vivek.trade.risk.grpc.ValidationRequest.newBuilder()
                .setOrderId(order.getOrderId())
                .setUserId(order.getUserId() != null ? order.getUserId() : "")
                .setSymbol(order.getSymbol() != null ? order.getSymbol() : "")
                .setSide(order.getSide() != null ? order.getSide().name() : "")
                .setQuantity(order.getQuantity())
                .setPrice(order.getPrice())
                .build();
        return toCompletableFuture(riskStub.validate(request))
                .thenApply(res -> TaskResult.builder()
                        .serviceId(res.getServiceId())
                        .success(res.getSuccess())
                        .reason(res.getReason())
                        .latencyMs(res.getLatencyMs())
                        .build());
    }

    @CircuitBreaker(name = "marginService", fallbackMethod = "marginFallback")
    CompletableFuture<TaskResult> callMargin(Order order) {
        org.vivek.trade.margin.grpc.ValidationRequest request = org.vivek.trade.margin.grpc.ValidationRequest.newBuilder()
                .setOrderId(order.getOrderId())
                .setUserId(order.getUserId() != null ? order.getUserId() : "")
                .setSymbol(order.getSymbol() != null ? order.getSymbol() : "")
                .setSide(order.getSide() != null ? order.getSide().name() : "")
                .setQuantity(order.getQuantity())
                .setPrice(order.getPrice())
                .setOrderType(order.getOrderType() != null ? order.getOrderType().name() : "")
                .build();
        return toCompletableFuture(marginStub.validate(request))
                .thenApply(res -> TaskResult.builder()
                        .serviceId(res.getServiceId())
                        .success(res.getSuccess())
                        .reason(res.getReason())
                        .latencyMs(res.getLatencyMs())
                        .build());
    }

    @CircuitBreaker(name = "complianceService", fallbackMethod = "complianceFallback")
    CompletableFuture<TaskResult> callCompliance(Order order) {
        org.vivek.trade.compliance.grpc.ValidationRequest request = org.vivek.trade.compliance.grpc.ValidationRequest.newBuilder()
                .setOrderId(order.getOrderId())
                .setUserId(order.getUserId() != null ? order.getUserId() : "")
                .setSymbol(order.getSymbol() != null ? order.getSymbol() : "")
                .setSide(order.getSide() != null ? order.getSide().name() : "")
                .setQuantity(order.getQuantity())
                .setPrice(order.getPrice())
                .build();
        return toCompletableFuture(complianceStub.validate(request))
                .thenApply(res -> TaskResult.builder()
                        .serviceId(res.getServiceId())
                        .success(res.getSuccess())
                        .reason(res.getReason())
                        .latencyMs(res.getLatencyMs())
                        .build());
    }

    CompletableFuture<TaskResult> riskFallback(Order order, CallNotPermittedException e) {
        log.warn("Circuit OPEN for risk-service, failing fast");
        return CompletableFuture.completedFuture(TaskResult.builder()
                .serviceId("risk-service")
                .success(false)
                .reason("CIRCUIT_OPEN: risk-service unavailable")
                .latencyMs(0L)
                .build());
    }

    CompletableFuture<TaskResult> marginFallback(Order order, CallNotPermittedException e) {
        log.warn("Circuit OPEN for margin-service, failing fast");
        return CompletableFuture.completedFuture(TaskResult.builder()
                .serviceId("margin-service")
                .success(false)
                .reason("CIRCUIT_OPEN: margin-service unavailable")
                .latencyMs(0L)
                .build());
    }

    CompletableFuture<TaskResult> complianceFallback(Order order, CallNotPermittedException e) {
        log.warn("Circuit OPEN for compliance-service, failing fast");
        return CompletableFuture.completedFuture(TaskResult.builder()
                .serviceId("compliance-service")
                .success(false)
                .reason("CIRCUIT_OPEN: compliance-service unavailable")
                .latencyMs(0L)
                .build());
    }

    private <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> listenableFuture) {
        CompletableFuture<T> completableFuture = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean result = listenableFuture.cancel(mayInterruptIfRunning);
                super.cancel(mayInterruptIfRunning);
                return result;
            }
        };
        listenableFuture.addListener(() -> {
            try {
                completableFuture.complete(listenableFuture.get());
            } catch (ExecutionException e) {
                completableFuture.completeExceptionally(e.getCause());
            } catch (InterruptedException e) {
                completableFuture.completeExceptionally(e);
            }
        }, executorService);
        return completableFuture;
    }
}
