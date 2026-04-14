# Risk Service

risk-service is the synchronous pre-trade risk validator in the order DAG.
It exposes a gRPC Validate API used by order-service before routing orders to the matching engine.
It also consumes post-trade events from Kafka to keep in-memory positions updated.

## Service Role

- Pre-trade risk validation over gRPC.
- Enforces per-order and per-position risk limits.
- Exposes REST endpoints for position/risk inspection and manual position updates.
- Consumes trade executions to adjust buyer/seller positions.

## Ports And Protocols

- gRPC: 9091
- REST: 8090
- Kafka consumer group: risk-group

## gRPC Contract

Proto file:
- ../proto/risk.proto

Service:
- trade.risk.RiskService
- Validate(ValidationRequest) -> ValidationResponse

ValidationRequest fields:
- order_id
- user_id
- symbol
- side
- quantity
- price

ValidationResponse fields:
- success
- service_id
- reason
- latency_ms

## Current Validation Logic

Implementation:
- src/main/java/org/vivek/riskservice/service/RiskServiceImpl.java

Configured constants in code:
- MAX_POSITION_LIMIT = 10000 shares per user per symbol
- MAX_ORDER_VALUE = 500000.0
- MAX_DAILY_LOSS = -25000.0
- MAX_EXPOSURE_MULTIPLIER = 5.0 (defined but not currently enforced)

Checks performed in validate:

1. Order value check
- Reject when quantity * price > MAX_ORDER_VALUE

2. Daily PnL check
- Reject when user daily PnL <= MAX_DAILY_LOSS

3. Position limit check (BUY only)
- Reject when projected position (current + quantity) > MAX_POSITION_LIMIT

4. Short-sell check (SELL only)
- Reject when sell quantity > current held position

On pass:
- Returns success=true with reason "All risk checks passed"

## In-Memory State

Bootstrapped at startup via @PostConstruct:

- Positions:
  - U1: INFY=8000, TCS=500, RELIANCE=200
  - U2: INFY=0, TCS=1000
  - U3: empty
- Daily PnL:
  - U1=-8000
  - U2=5000
  - U3=0

State stores:
- userPositions: userId -> (symbol -> quantity)
- userDailyPnL: userId -> pnl

All state is in-memory and resets on service restart.

## Kafka Consumer

Listener class:
- src/main/java/org/vivek/riskservice/listener/TradeExecutionListener.java

Topic consumed:
- trade-executed

Behavior:
- buyerId position is increased by trade quantity.
- sellerId position is decreased by trade quantity.

Consumer config:
- src/main/java/org/vivek/riskservice/config/KafkaConsumerConfig.java
- JSON deserialization with trusted packages set to *

## REST API

Controller:
- src/main/java/org/vivek/riskservice/controller/RiskController.java

Base path:
- /api/v1/risk

Endpoints:

- GET /api/v1/risk/positions/{userId}
  - Returns current positions map for the user.

- GET /api/v1/risk/config
  - Returns risk constants.

- PUT /api/v1/risk/positions/{userId}/{symbol}?quantity=X
  - Sets absolute position quantity for user+symbol.

Example:

```bash
curl http://localhost:8090/api/v1/risk/config
curl http://localhost:8090/api/v1/risk/positions/U1
curl -X PUT "http://localhost:8090/api/v1/risk/positions/U1/INFY?quantity=9000"
```

## Configuration

From src/main/resources/application.yml:

- server.port=8090
- grpc.server.port=9091
- spring.application.name=risk-service
- spring.kafka.bootstrap-servers=localhost:9092
- spring.kafka.consumer.group-id=risk-group
- spring.kafka.consumer.auto-offset-reset=earliest
- management.endpoints.web.exposure.include=health

## Build And Run

From repo root:

```bash
mvn -pl risk-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl risk-service test
```

Current test set:
- Spring context load test only.

Test file:
- src/test/java/org/vivek/riskservice/RiskServiceApplicationTests.java

## Notes

- gRPC responses include measured latency from request start to response.
- The service currently does not persist risk state to a database.
- The MAX_EXPOSURE_MULTIPLIER constant is present for future enhancement but is not applied in validation yet.
