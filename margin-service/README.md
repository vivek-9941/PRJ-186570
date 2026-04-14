# Margin Service

`margin-service` is the pre-trade funds validator.
It exposes a gRPC `Validate` API for order-service DAG checks and maintains in-memory margin state, including cash, collateral, and reserved funds.

## Service Role

- Pre-trade synchronous margin validation over gRPC.
- Reserves required margin on accepted validations to prevent double spending.
- Listens to post-trade and cancellation Kafka events to release reservations and settle cash.
- Exposes REST endpoints for margin snapshot, deposit, and withdrawal.

## Ports and Protocols

- HTTP (REST + actuator): `8092`
- gRPC server: `9094`

## gRPC Contract

Proto file:
- `../proto/margin.proto`

Service:
- `trade.margin.MarginService`
- `Validate(ValidationRequest) -> ValidationResponse`

`ValidationRequest` includes:
- `order_id`, `user_id`, `symbol`, `side`, `quantity`, `price`, `order_type`

`ValidationResponse` includes:
- `success`, `service_id`, `reason`, `latency_ms`

## Current Implementation

Main class:
- `src/main/java/org/vivek/marginservice/service/MarginServiceImpl.java`

In-memory stores:
- `userCashBalance`
- `reservedMargin` (per user)
- `holdingsValue`
- `orderReservations` (orderId -> reserved amount + user)

Bootstrap data (`@PostConstruct`):
- Cash balances: `U1=100000`, `U2=250000`, `U3=50000`
- Reserved margin: all zero
- Holdings collateral: `U1=150000`, `U2=80000`, `U3=0`

### Validation Logic

- Margin rate: `IOC`/`MIS`/`INTRADAY` -> `0.2`, `LIMIT`/`CNC`/`DELIVERY`/`GTD` -> `1.0`
- Required margin formula: `quantity * price * marginRate`
- Margin is required for all `BUY` orders, and for `SELL` when order type is intraday (`IOC`)
- Available margin formula: `cash + holdingsValue - reservedMargin`

Outcomes:
- BUY insufficient -> `INSUFFICIENT_MARGIN`
- IOC SELL insufficient -> `INSUFFICIENT_MARGIN_FOR_SHORT`
- Success -> reserve margin and return `MARGIN_OK: required=... available=... reserved=...`

## Kafka Consumers

Class:
- `src/main/java/org/vivek/marginservice/service/MarginServiceImpl.java`

Consumers:
- `trade-executed` (`groupId=margin-group`): releases reservation for `buyOrderId`/`sellOrderId`, debits buyer cash, credits seller cash
- `order-cancelled` (`groupId=margin-group`): releases reservation for cancelled order

Consumer config:
- `src/main/java/org/vivek/marginservice/config/KafkaConsumerConfig.java`
- Uses JSON deserialization with trusted packages `*`
- No custom retry/DLT handler in this service currently

## REST API

Controller:
- `src/main/java/org/vivek/marginservice/controller/MarginController.java`
- Base path: `/api/v1/margin`

Endpoints:
- `GET /{userId}` -> margin snapshot
- `PUT /{userId}/deposit?amount=X` -> deposit funds
- `PUT /{userId}/withdraw?amount=X` -> withdraw funds

Margin snapshot fields:
- `cashBalance`
- `holdingsValue`
- `reservedMargin`
- `availableMargin`
- `totalNetworth`

Example calls:

```bash
curl http://localhost:8092/api/v1/margin/U1
curl -X PUT "http://localhost:8092/api/v1/margin/U1/deposit?amount=5000"
curl -X PUT "http://localhost:8092/api/v1/margin/U1/withdraw?amount=2500"
```

## Configuration

From `src/main/resources/application.yml`:

- `server.port=8092`
- `grpc.server.port=9094`
- `spring.application.name=margin-service`
- `spring.kafka.bootstrap-servers=localhost:9092`
- `spring.kafka.consumer.group-id=margin-group`
- `spring.kafka.consumer.auto-offset-reset=earliest`
- `management.endpoints.web.exposure.include=health`

## Build and Run

From repo root:

```bash
mvn -pl margin-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl margin-service test
```

Current tests cover:
- Delivery BUY reservation success
- Insufficient margin rejection
- Intraday short sell rejection
- Trade execution settlement + reservation release
- Cancellation reservation release
- Deposit and withdraw flows
- Withdraw insufficient funds error

Test files:
- `src/test/java/org/vivek/marginservice/service/MarginServiceImplTest.java`
- `src/test/java/org/vivek/marginservice/MarginServiceApplicationTests.java`

## Notes

- All margin state is in memory and resets on restart.
- Unknown `side` defaults to `BUY`.
- Unknown `orderType` defaults to delivery rules (`LIMIT`).
- Currency formatting in reason/log strings currently appears as mojibake (`₹`) due to encoding in source strings.
