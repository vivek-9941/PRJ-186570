package com.trade.orderservice.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.vivek.trade.compliance.grpc.ComplianceServiceGrpc;
import org.vivek.trade.margin.grpc.MarginServiceGrpc;
import org.vivek.trade.risk.grpc.RiskServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Value("${grpc.client.risk-service.address:static://localhost:9091}")
    private String riskAddress;

    @Value("${grpc.client.margin-service.address:static://localhost:9092}")
    private String marginAddress;

    @Value("${grpc.client.compliance-service.address:static://localhost:9093}")
    private String complianceAddress;

    @Bean
    public ManagedChannel riskChannel() {
        HostPort target = parseAddress(riskAddress);
        return ManagedChannelBuilder.forAddress(target.host(), target.port())
                .usePlaintext()
                .build();
    }

    @Bean
    public RiskServiceGrpc.RiskServiceFutureStub riskServiceFutureStub(ManagedChannel riskChannel) {
        return RiskServiceGrpc.newFutureStub(riskChannel);
    }

    @Bean
    public ManagedChannel marginChannel() {
        HostPort target = parseAddress(marginAddress);
        return ManagedChannelBuilder.forAddress(target.host(), target.port())
                .usePlaintext()
                .build();
    }

    @Bean
    public MarginServiceGrpc.MarginServiceFutureStub marginServiceFutureStub(ManagedChannel marginChannel) {
        return MarginServiceGrpc.newFutureStub(marginChannel);
    }

    @Bean
    public ManagedChannel complianceChannel() {
        HostPort target = parseAddress(complianceAddress);
        return ManagedChannelBuilder.forAddress(target.host(), target.port())
                .usePlaintext()
                .build();
    }

    @Bean
    public ComplianceServiceGrpc.ComplianceServiceFutureStub complianceServiceFutureStub(ManagedChannel complianceChannel) {
        return ComplianceServiceGrpc.newFutureStub(complianceChannel);
    }

    private HostPort parseAddress(String address) {
        String normalized = address == null ? "" : address.trim();
        if (normalized.startsWith("static://")) {
            normalized = normalized.substring("static://".length());
        }

        int separator = normalized.lastIndexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Invalid gRPC address: " + address);
        }

        String host = normalized.substring(0, separator);
        int port = Integer.parseInt(normalized.substring(separator + 1));
        return new HostPort(host, port);
    }

    private record HostPort(String host, int port) {}
}
