# Risk Service Package Overview

This package contains the core implementation of the risk-service module.
It serves the pre-trade risk decision in the DAG through gRPC and updates positions from post-trade Kafka events.

## Package Structure

- RiskServiceApplication.java
  - Spring Boot entrypoint.

- config/
  - KafkaConsumerConfig.java
  - Kafka consumer factory and listener container configuration.

- service/
  - RiskServiceImpl.java
  - gRPC RiskService implementation and risk-check logic.

- listener/
  - TradeExecutionListener.java
  - Kafka listener for trade-executed events.

- controller/
  - RiskController.java
  - REST endpoints for inspecting risk state and updating positions.

## Runtime Flow

1. order-service invokes RiskService.Validate over gRPC.
2. RiskServiceImpl runs deterministic checks:
   - max order value
   - daily loss threshold
   - buy-side projected position limit
   - sell-side short position check
3. Returns ValidationResponse with success/reason/latency.
4. Later, TradeExecutionListener consumes trade-executed events and adjusts positions for buyer and seller.

## Data Ownership

In-memory maps owned by RiskServiceImpl:

- userPositions: userId -> symbol -> quantity
- userDailyPnL: userId -> pnl

These are seeded on startup and are not persisted.

## APIs In This Package

gRPC:
- trade.risk.RiskService.Validate(ValidationRequest)

REST:
- GET /api/v1/risk/positions/{userId}
- GET /api/v1/risk/config
- PUT /api/v1/risk/positions/{userId}/{symbol}?quantity=X

Kafka:
- Consumes topic trade-executed with group risk-group

## Notes

- Validation is synchronous and intended for low-latency pre-trade checks.
- Post-trade updates are asynchronous through Kafka.
- Risk constants are currently code-level constants in RiskServiceImpl.
