package org.vivek.commonmodule.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskResult {
    private String serviceId;
    private boolean success;
    private String reason;
    private long latencyMs;
}
