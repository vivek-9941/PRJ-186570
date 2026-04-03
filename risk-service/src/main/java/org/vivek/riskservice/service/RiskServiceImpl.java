package org.vivek.riskservice.service;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.vivek.trade.risk.grpc.RiskServiceGrpc;
import org.vivek.trade.risk.grpc.ValidationRequest;
import org.vivek.trade.risk.grpc.ValidationResponse;

import jakarta.annotation.PostConstruct;
import java.util.Random;

@GrpcService
@Slf4j
public class RiskServiceImpl extends RiskServiceGrpc.RiskServiceImplBase {

    private final Random random = new Random();

    @Value("${grpc.server.port:9091}")
    private int port;

    @PostConstruct
    public void init() {
        log.info("RiskService gRPC server initialized and configured to run on port: {}", port);
        // Note: net.devh.boot.grpc.server.service.GrpcService automatically starts the server.
    }

    @Override
    public void validate(ValidationRequest request, StreamObserver<ValidationResponse> responseObserver) {
        log.info("Received Risk Validation request for order: {}", request.getOrderId());
        long start = System.currentTimeMillis();

        try {
            // 1. Simulate a random latency between 50–200ms
            int latency = 50 + random.nextInt(151);
            Thread.sleep(latency);

            boolean isSuccess = true;
            String reason = "Risk checks passed";

            // 2. Have a 10% random failure rate
            if (random.nextDouble() < 0.1) {
                isSuccess = false;
                reason = "Random Risk check failure simulating load";
            }

            long elapsed = System.currentTimeMillis() - start;

            // 3 & 4. Return success/failure with reason
            ValidationResponse response = ValidationResponse.newBuilder()
                    .setSuccess(isSuccess)
                    .setServiceId("risk-service")
                    .setReason(reason)
                    .setLatencyMs(elapsed)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Interrupted during risk processing")
                    .asRuntimeException());
        }
    }
}
