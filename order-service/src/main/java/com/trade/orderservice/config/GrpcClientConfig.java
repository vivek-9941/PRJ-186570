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

    @Value("${grpc.risk.host:localhost}")
    private String riskHost;
    @Value("${grpc.risk.port:9091}")
    private int riskPort;

    @Value("${grpc.margin.host:localhost}")
    private String marginHost;
    @Value("${grpc.margin.port:9092}")
    private int marginPort;

    @Value("${grpc.compliance.host:localhost}")
    private String complianceHost;
    @Value("${grpc.compliance.port:9093}")
    private int compliancePort;

    @Bean
    public ManagedChannel riskChannel() {
        return ManagedChannelBuilder.forAddress(riskHost, riskPort)
                .usePlaintext()
                .build();
    }

    @Bean
    public RiskServiceGrpc.RiskServiceFutureStub riskServiceFutureStub(ManagedChannel riskChannel) {
        return RiskServiceGrpc.newFutureStub(riskChannel);
    }

    @Bean
    public ManagedChannel marginChannel() {
        return ManagedChannelBuilder.forAddress(marginHost, marginPort)
                .usePlaintext()
                .build();
    }

    @Bean
    public MarginServiceGrpc.MarginServiceFutureStub marginServiceFutureStub(ManagedChannel marginChannel) {
        return MarginServiceGrpc.newFutureStub(marginChannel);
    }

    @Bean
    public ManagedChannel complianceChannel() {
        return ManagedChannelBuilder.forAddress(complianceHost, compliancePort)
                .usePlaintext()
                .build();
    }

    @Bean
    public ComplianceServiceGrpc.ComplianceServiceFutureStub complianceServiceFutureStub(ManagedChannel complianceChannel) {
        return ComplianceServiceGrpc.newFutureStub(complianceChannel);
    }
}
