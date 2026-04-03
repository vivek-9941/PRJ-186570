package org.vivek.commonmodule.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DAGResult {
    private String orderId;
    private List<TaskResult> taskResults;
    private boolean allPassed;
    private String finalReason;

    public static DAGResult from(String orderId, List<TaskResult> results) {
        boolean passed = true;
        String reason = "ALL_CHECKS_PASSED";

        for (TaskResult tr : results) {
            if (!tr.isSuccess()) {
                passed = false;
                if ("ALL_CHECKS_PASSED".equals(reason)) {
                    reason = tr.getReason(); // Pick the first failed reason
                }
                break; // Break here if we just need *a* reason, but keep traversing if we care about modifying something else
            }
        }

        // Wait, loop again to make absolutely sure all passed is set perfectly without break bug
        passed = results.stream().allMatch(TaskResult::isSuccess);
        reason = results.stream()
                .filter(tr -> !tr.isSuccess())
                .findFirst()
                .map(TaskResult::getReason)
                .orElse("ALL_CHECKS_PASSED");

        return DAGResult.builder()
                .orderId(orderId)
                .taskResults(results)
                .allPassed(passed)
                .finalReason(reason)
                .build();
    }
}
