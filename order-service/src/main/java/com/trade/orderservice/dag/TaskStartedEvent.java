package com.trade.orderservice.dag;

import org.springframework.context.ApplicationEvent;

public class TaskStartedEvent extends ApplicationEvent {
    private final String orderId;
    private final String serviceId;

    public TaskStartedEvent(Object source, String orderId, String serviceId) {
        super(source);
        this.orderId = orderId;
        this.serviceId = serviceId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getServiceId() {
        return serviceId;
    }
}
