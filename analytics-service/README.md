# Analytics Service

`analytics-service` is the post-trade read model for symbol-level market analytics.
It consumes `TradeExecution` events from Kafka, maintains in-memory per-symbol aggregates, and exposes REST endpoints for dashboard consumption.

## What This Service Does

- Consumes executed trades from Kafka topic `trade-executed`.
- Maintains per-symbol stats in memory:
  - `totalTrades`
  - `totalVolume`
  - `lastPrice`
  - `avgPrice`
- Applies idempotency for trade events using `tradeId` (duplicate events are skipped).
- Monitors dead-letter traffic (`*.DLT` topics) and exposes DLQ status.
- Publishes Prometheus-compatible actuator metrics (including `dlq.messages`).

## Implementation Overview

### Core Classes

- `AnalyticsConsumer`:
  - File: `src/main/java/org/vivek/analyticsservice/AnalyticsConsumer.java`
  - `@KafkaListener` on `${kafka.consumer.topic}` (default: `trade-executed`), group `analytics-group`.
  - Stores stats in `ConcurrentHashMap<String, SymbolStats>`.
  - Uses an in-memory `Set<String> processedTradeIds` to enforce idempotency.

- `SymbolStats`:
  - File: `src/main/java/org/vivek/analyticsservice/SymbolStats.java`
  - DTO for aggregate fields: `totalTrades`, `totalVolume`, `lastPrice`, `avgPrice`.

- `DltMonitor`:
  - File: `src/main/java/org/vivek/analyticsservice/DltMonitor.java`
  - `@KafkaListener` on:
    - `trade-executed.DLT`
    - `order-cancelled.DLT`
  - Tracks per-topic DLQ count and last error message.
  - Increments Micrometer counter `dlq.messages` tagged by topic.

- `KafkaConsumerConfig`:
  - File: `src/main/java/org/vivek/analyticsservice/KafkaConsumerConfig.java`
  - Configures consumer + DLT producer + error handler.
  - Retry delays: `1s`, `3s`, `10s`.
  - After retries, routes failed records to `<original-topic>.DLT`.
  - Adds DLQ headers:
    - `X-Exception-Message`
    - `X-Exception-Stacktrace` (truncated)
    - `X-Original-Offset`
    - `X-Failed-At`

- `AnalyticsController`:
  - File: `src/main/java/org/vivek/analyticsservice/AnalyticsController.java`
  - Exposes analytics and DLQ REST endpoints.

- `CorsConfig`:
  - File: `src/main/java/org/vivek/analyticsservice/config/CorsConfig.java`
  - Allows dashboard origins: `http://localhost:5173`, `http://localhost:3000`.

## API Endpoints

Base path: `/api/v1/analytics`

- `GET /symbols`
  - Returns all symbol stats as `Map<String, SymbolStats>`.

- `GET /symbols/{symbol}`
  - Returns stats for one symbol, or `null` if not seen yet.

- `GET /dlq`
  - Returns per-DLT-topic status:
    - `count`
    - `lastErrorMessage`

### Example Calls

```bash
curl http://localhost:8085/api/v1/analytics/symbols
curl http://localhost:8085/api/v1/analytics/symbols/AAPL
curl http://localhost:8085/api/v1/analytics/dlq
```

## Configuration

From `src/main/resources/application.yml`:

- `server.port`: `8085`
- `spring.application.name`: `analytics-service`
- `spring.kafka.bootstrap-servers`: `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- `spring.kafka.consumer.group-id`: `analytics-group`
- `spring.kafka.consumer.auto-offset-reset`: `earliest`
- `kafka.consumer.topic`: `trade-executed`

## Running Locally

From repo root:

```bash
mvn -pl analytics-service -am spring-boot:run
```

Or from `analytics-service/`:

```bash
mvn spring-boot:run
```

## Testing

```bash
mvn -pl analytics-service test
```

Current unit tests validate:

- Duplicate trade id handling (`AnalyticsConsumerTest`)
- DLQ counting and error extraction (`DltMonitorTest`)
- Spring context load (`AnalyticsServiceApplicationTests`)

## Notes and Limitations

- Stats and processed trade IDs are in-memory only (reset on restart).
- `avgPrice` is trade-count weighted, not quantity-weighted (not VWAP).
- `GET /symbols/{symbol}` returns `null` when symbol has no trades.
