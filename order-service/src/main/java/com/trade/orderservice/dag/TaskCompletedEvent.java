package com.trade.orderservice.dag;

import org.springframework.context.ApplicationEvent;

public class TaskCompletedEvent extends ApplicationEvent {
    private final String orderId;
    private final String serviceId;
    private final boolean success;
    private final long latencyMs;

    public TaskCompletedEvent(Object source, String orderId, String serviceId, boolean success, long latencyMs) {
        super(source);
        this.orderId = orderId;
        this.serviceId = serviceId;
        this.success = success;
        this.latencyMs = latencyMs;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLatencyMs() {
        return latencyMs;
    }
}
