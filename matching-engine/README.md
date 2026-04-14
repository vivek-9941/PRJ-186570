# Matching Engine

`matching-engine` is the post-validation execution service in this project.
It receives approved orders from `order-service`, performs in-memory price-time matching, and publishes post-trade events to Kafka.

## Service Role In The System

Based on the project design in the root `info` document:

- Pre-trade checks are synchronous in `order-service` (risk, margin, compliance).
- After approval, orders are routed to this service over REST.
- This service executes matching immediately and emits Kafka events for downstream consumers (`ledger-service`, `notification-service`, `analytics-service`, and others).

In short: this module is the trade execution boundary between synchronous order routing and asynchronous post-trade processing.

## Port And Protocol

- HTTP: `8081`
- Messaging: Kafka producer (`trade-executed`, `order-cancelled`)

## Main Components

- `MatchingController`
  - API endpoints for matching, cancellation, and order book inspection.
- `OrderBookRegistry`
  - Manages one `SymbolOrderBook` per symbol.
  - Seeds bootstrap liquidity for default symbols on startup.
- `SymbolOrderBook`
  - Core matching logic with partial fills and order-state transitions.
- `KafkaProducerConfig`
  - Kafka producer templates and topic beans.

## Matching Model

Each symbol has an isolated in-memory order book:

- Bid side: `ConcurrentSkipListMap` in descending order (highest bid first).
- Ask side: `ConcurrentSkipListMap` in ascending order (lowest ask first).
- FIFO per price level via `ConcurrentLinkedQueue`.

Matching rules:

- Incoming `BUY` matches as long as `bestAsk <= buyPrice`.
- Incoming `SELL` matches as long as `bestBid >= sellPrice`.
- One incoming order can generate multiple `TradeExecution` events across multiple price levels.

Order type behavior:

- `LIMIT` and `GTD`
  - Any remainder is added back as a resting order.
- `IOC`
  - Unfilled remainder is cancelled immediately and not re-queued.
  - Cancellation event is published.

## REST API

Base path: `/api/v1`

### 1) Match Order

- `POST /api/v1/match`
- Body: `Order` from `common-module`
- Behavior:
  - Matches against the symbol book.
  - Publishes each `TradeExecution` to topic `trade-executed` with key = incoming `orderId`.
- Response:
  - `matched` (boolean)
  - `fillCount` (number)
  - `totalFilled` (number)
  - `remainingQty` (number)
  - `executions` (list of trade executions)

Example request:

```bash
curl -X POST http://localhost:8081/api/v1/match \
  -H "Content-Type: application/json" \
  -d '{
    "orderId":"ORD-1001",
    "userId":"U1",
    "symbol":"INFY",
    "side":"BUY",
    "quantity":25,
    "price":1780,
    "orderType":"LIMIT",
    "status":"PENDING"
  }'
```

### 2) Cancel Resting Order

- `DELETE /api/v1/orders/{orderId}`
- Optional query param: `symbol`
- Behavior:
  - If `symbol` is provided, cancel is attempted only in that symbol book.
  - Otherwise scans all books for the order.
  - On success, publishes `CancellationEvent` to `order-cancelled`.
- Response: `{ "cancelled": true|false }`

Example:

```bash
curl -X DELETE "http://localhost:8081/api/v1/orders/ORD-1001?symbol=INFY"
```

### 3) Order Book Snapshot By Symbol

- `GET /api/v1/orderbook/{symbol}`
- Response includes:
  - `bestBid`, `bestAsk`, `spread`
  - top buy/sell levels
  - `totalBuyQty`, `totalSellQty`

### 4) All Order Books

- `GET /api/v1/orderbook`
- Returns snapshots for all initialized symbols/books.

### 5) Depth View

- `GET /api/v1/orderbook/{symbol}/depth`
- Returns top levels (`bids`, `asks`) with `spread` and `midPrice`.

## Kafka Integration

Producer config is JSON-based with idempotent producer settings:

- `acks=all`
- `retries=3`
- `enable.idempotence=true`

Topics created by this service:

- `trade-executed` (3 partitions)
- `order-cancelled` (3 partitions)

Published events:

- `TradeExecution` for each fill.
- `CancellationEvent` for explicit cancel and IOC unfilled remainder.

## Bootstrap Liquidity

On startup, the registry seeds synthetic sell liquidity for:

- `INFY`
- `TCS`
- `RELIANCE`
- `HDFC`

This helps demo flows without placing initial sell orders manually.

Config keys:

- `matching.orderbook.bootstrap.orders-per-symbol` (default `10`)
- `matching.orderbook.bootstrap.price-step-ratio` (default `0.001`)

## Configuration

From `src/main/resources/application.yml`:

- `server.port=8081`
- `spring.application.name=matching-engine`
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- `management.endpoints.web.exposure.include=health,prometheus`

CORS allows frontend development origins:

- `http://localhost:5173`
- `http://localhost:3000`

## Build And Run

From repo root:

```bash
mvn -pl matching-engine -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl matching-engine test
```

Current test coverage includes:

- Price-time priority matching.
- No-match queuing behavior.
- Partial fill remainder handling.
- Multi-level fills.
- IOC remainder cancellation behavior.
- Order cancellation.
- Concurrent matching safety.
- Controller response shaping and Kafka publish interactions.

## Notes And Limitations

- State is in-memory; order books reset on restart.
- Cancel lookup scans price levels (`O(n)`), as noted in code comments.
- Matching methods are synchronized per symbol book for consistency.