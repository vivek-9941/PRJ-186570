# Order Service

`order-service` is the orchestration core of the project.
It receives client order requests, executes pre-trade validation in parallel via gRPC (risk, margin, compliance), routes approved orders to the matching engine, maintains order lifecycle state, and manages GTD expiry.

## Service Role

- Exposes order APIs (`place`, `get`, `list by user`, `cancel`).
- Runs DAG-style pre-trade checks in parallel using `CompletableFuture`.
- Uses resilience patterns: timeout, retry, and circuit breakers on validation calls.
- Routes approved orders to matching engine over HTTP.
- Publishes `order-expired` Kafka events from scheduler.
- Exposes circuit breaker health for dashboard observability.

## Ports and Integrations

- HTTP server: `8080`
- Outbound gRPC clients: risk `9091`, margin `9094`, compliance `9093` (via `RISK_HOST`, `MARGIN_HOST`, `COMPLIANCE_HOST`)
- Outbound matching engine HTTP: `http://${MATCHING_ENGINE_HOST:localhost}:8081`
- Kafka bootstrap: `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- Publishes topic: `order-expired`

## Core Lifecycle

Order statuses used (`OrderStatus`):
- `PENDING`
- `VALIDATING`
- `APPROVED`
- `REJECTED`
- `ROUTED`
- `PARTIALLY_FILLED`
- `CANCELLED`
- `EXPIRED`
- `EXECUTED`
- `FAILED`

Typical flow (`LIMIT/GTD` happy path):
1. `POST /api/v1/orders` stores order as `PENDING` and returns `202`.
2. Async processing sets `VALIDATING`.
3. DAG executor runs risk + margin + compliance in parallel.
4. If all pass -> `APPROVED` -> `ROUTED` -> route to matching engine.
5. Matching response maps to final status (`EXECUTED`, `PARTIALLY_FILLED`, or back to `PENDING`).

Failure path:
- Any failed validation -> `REJECTED` with reason from `DAGResult`.
- Exceptional DAG/matching errors -> `FAILED` with rejection reason.

## API Endpoints

Controller:
- `src/main/java/org/vivek/order/controller/OrderController.java`
- Base path: `/api/v1/orders`

Endpoints:
- `POST /api/v1/orders`
- Request: `PlaceOrderRequest`
- Returns `202 Accepted` with `{orderId,status,message}`
- Order processing happens async.

- `GET /api/v1/orders/{orderId}`
- Returns order or `404`.

- `GET /api/v1/orders/user/{userId}`
- Returns all orders for a user.

- `DELETE /api/v1/orders/{orderId}`
- Allows cancel only when status is `PENDING` or `PARTIALLY_FILLED`.
- Otherwise returns `409` with error.
- Calls matching engine cancel endpoint and then marks order `CANCELLED`.

Validation DTO:
- `src/main/java/org/vivek/order/dto/PlaceOrderRequest.java`
- Required: `userId`, `symbol`, `side`, `orderType`, positive `quantity`, positive `price`.

GTD handling at create time:
- If `orderType=GTD` and no `expiryTime` is provided, defaults to `today 17:00`.

## DAG Execution (Pre-Trade)

Implementation:
- `src/main/java/com/trade/orderservice/dag/DAGExecutor.java`

Behavior:
- Launches three checks in parallel: `risk-service`, `margin-service`, `compliance-service`
- Uses named worker pool `dag-worker-*` (fixed size 10).
- Per call timeout: `500ms`.
- Retry policy: up to `2` retries with `100ms` delay.
- Emits task events: `TaskStartedEvent`, `TaskCompletedEvent`
- Aggregates results using `DAGResult.from(orderId, results)`.
- `@Timed("dag.execution")` for Micrometer timing.

Circuit breakers (Resilience4j):
- `riskService`
- `marginService`
- `complianceService`

Fallback behavior on open circuit:
- returns failed `TaskResult` with reason `CIRCUIT_OPEN: ... unavailable`.

## Routing to Matching Engine

Client:
- `src/main/java/org/vivek/order/client/MatchingEngineClient.java`

Endpoints used:
- Route: `POST {matching-engine.url}/api/v1/match`
- Cancel: `DELETE {matching-engine.url}/api/v1/orders/{orderId}`

If route call fails or returns non-2xx body:
- service marks order as `FAILED` with `MATCHING_ENGINE_UNAVAILABLE`.

Matching outcome mapping (`OrderService.applyMatchingOutcome`):
- IOC with remainder -> `CANCELLED` (and `PARTIALLY_FILLED` transiently if partial fill happened)
- Filled fully -> `EXECUTED`
- Partial fill -> `PARTIALLY_FILLED`
- No fill -> `PENDING`

## GTD Expiry Scheduler

Implementation:
- `src/main/java/org/vivek/order/service/OrderExpiryScheduler.java`

Schedules:
- End-of-day: `@Scheduled(cron = "0 0 17 * * MON-FRI")`
- Intraday stale scan: `@Scheduled(fixedRate = 60000)` during trading-hours window

Trading-hours gate for stale run:
- Weekdays only
- Time in `[09:00, 17:00)` using injected `Clock`

Expiry targets:
- `OrderType.GTD`
- Status in `PENDING` or `PARTIALLY_FILLED`

On expiry:
1. Calls matching engine cancel.
2. Sets order status to `EXPIRED`.
3. Publishes `OrderExpiredEvent` to Kafka topic `order-expired`.

## Repository and State

Repository:
- `src/main/java/org/vivek/order/repository/OrderRepository.java`

Storage model:
- In-memory `ConcurrentHashMap<String, Order>`.
- Supports lookup by id, by user, and filtering for GTD expiry candidates.
- State resets on service restart.

## Health and Error APIs

Circuit breaker health:
- `GET /api/v1/health/circuit-breakers`
- Returns per-breaker state and failure rate, plus buffered calls in HALF_OPEN.

Global exception handler:
- `src/main/java/org/vivek/order/controller/GlobalExceptionHandler.java`
- Returns structured error payload: `error`, `message`, `orderId`, `timestamp`

## Kafka Producer Config

Config:
- `src/main/java/org/vivek/order/config/OrderKafkaProducerConfig.java`

Topic:
- `order-expired`
- partitions: `3`
- replicas: `1`

Producer reliability:
- `acks=all`
- `retries=3`
- `enable.idempotence=true`

## Configuration Highlights

From `src/main/resources/application.yml`:
- `server.port=8080`
- gRPC client addresses for risk/margin/compliance
- `matching-engine.url=http://${MATCHING_ENGINE_HOST:localhost}:8081`
- Resilience4j circuit breaker thresholds and open-state wait
- Actuator exposure: `health,prometheus,info`
- CORS allows dashboard origins (`5173`, `3000`) for `/api/**`

## Build and Run

From repo root:

```bash
mvn -pl order-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl order-service test
```

Current tests cover:
- DAG executor success/failure/parallel-invocation/timeout behavior
- Order controller cancellation + GTD default expiry behavior
- Circuit breaker health endpoint payload
- GTD expiry scheduler behavior and Kafka event publishing
- Spring context startup

Test files:
- `src/test/java/com/trade/orderservice/dag/DAGExecutorTest.java`
- `src/test/java/org/vivek/order/controller/OrderControllerTest.java`
- `src/test/java/org/vivek/order/controller/CircuitBreakerHealthControllerTest.java`
- `src/test/java/org/vivek/order/service/OrderExpirySchedulerTest.java`
- `src/test/java/org/vivek/order/OrderServiceApplicationTests.java`

## Notes

- Order processing is async from controller (`CompletableFuture.runAsync`), so API ack does not imply completion.
- Repository is in-memory only; no durable persistence in current implementation.
- Logging pattern includes `%X{correlationId}`, but there is currently no request filter in this module that sets it.
- `order-service` uses margin gRPC port `9094` in this codebase.
