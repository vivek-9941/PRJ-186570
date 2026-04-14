# Ledger Service

`ledger-service` is a post-trade Kafka consumer that maintains an in-memory cash ledger, holdings, and per-user trade audit history.
It also listens for order-cancellation events to release reserved margin.

## Service Role In The System

- Consumes trade events from Kafka topic `trade-executed`.
- Consumes cancellation events from Kafka topic `order-cancelled`.
- Updates buyer/seller cash balances and symbol holdings on each trade.
- Maintains ledger entries for audit/history views.
- Exposes read APIs for balance, holdings, and ledger history.

## Current Implementation

Main consumer:
- `src/main/java/org/vivek/ledgerservice/LedgerConsumer.java`

In-memory stores:
- `userBalances: Map<String, Double>`
- `userHoldings: Map<String, Map<String, Double>>`
- `userLedgerHistory: Map<String, List<LedgerEntry>>`
- `reservedMarginByOrder: Map<String, Double>`
- `processedTradeIds: Set<String>` (trade idempotency)
- `processedCancelledOrders: Set<String>` (cancellation idempotency)

### Trade Processing

Kafka listener:
- Topic: `${kafka.consumer.topic}` (default `trade-executed`)
- Group: `ledger-group`

For each `TradeExecution`:
- Skips duplicate `tradeId` (warn log, no exception)
- Computes trade amount = `executedPrice * quantity`
- Buyer side (if `buyerId` present): decrease cash balance, increase holdings, and add `LedgerEntry` with `side=BUY`
- Seller side (if `sellerId` present): increase cash balance, decrease holdings, and add `LedgerEntry` with `side=SELL`

### Cancellation Processing

Kafka listener:
- Topic: `${kafka.consumer.cancellation-topic}` (default `order-cancelled`)
- Group: `ledger-group`

For each `CancellationEvent`:
- Skips duplicate `orderId`
- Looks up reserved amount in `reservedMarginByOrder`
- Credits released amount to cancelling user balance
- Logs margin release

## REST API

Controller:
- `src/main/java/org/vivek/ledgerservice/LedgerController.java`
- Base path: `/api/v1/ledger`

Endpoints:
- `GET /{userId}` -> `{ userId, balance }`
- `GET /{userId}/holdings` -> `{ symbol: quantity }`
- `GET /{userId}/history` -> `List<LedgerEntry>` sorted by newest first

Example calls:

```bash
curl http://localhost:8082/api/v1/ledger/U1
curl http://localhost:8082/api/v1/ledger/U1/holdings
curl http://localhost:8082/api/v1/ledger/U1/history
```

## Ledger Entry Model

File:
- `src/main/java/org/vivek/ledgerservice/LedgerEntry.java`

Fields:
- `entryId`
- `tradeId`
- `userId`
- `side` (`BUY` or `SELL`)
- `symbol`
- `quantity`
- `price`
- `amount`
- `balanceAfter`
- `timestamp`

## Kafka Reliability (DLT)

Config file:
- `src/main/java/org/vivek/ledgerservice/KafkaConsumerConfig.java`

Implemented behavior:
- Custom `DefaultErrorHandler` retry sequence: `1s`, `3s`, `10s`
- After retries, failed records are published to `<original-topic>.DLT`
- Added DLQ headers: `X-Exception-Message`, `X-Exception-Stacktrace` (truncated), `X-Original-Offset`, `X-Failed-At`

## Configuration

From `src/main/resources/application.yml`:

- `server.port=8082`
- `spring.application.name=ledger-service`
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- `spring.kafka.consumer.group-id=ledger-group`
- `spring.kafka.consumer.auto-offset-reset=earliest`
- `spring.kafka.consumer.properties.spring.json.trusted.packages=org.vivek.commonmodule.model`
- `kafka.consumer.topic=trade-executed`
- `kafka.consumer.cancellation-topic=order-cancelled`

## Build And Run

From repository root:

```bash
mvn -pl ledger-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl ledger-service test
```

Current tests validate:
- Cancellation releases reserved margin (`consumeCancellationReleasesReservedMargin`)
- Buyer/seller balance updates on trade (`consumeTradeExecutionUpdatesBuyerAndSellerBalances`)
- Duplicate trade id is ignored (`duplicateTradeIsIgnored`)

Test files:
- `src/test/java/org/vivek/ledgerservice/LedgerConsumerTest.java`
- `src/test/java/org/vivek/ledgerservice/LedgerServiceApplicationTests.java`

## Notes

- All data is in-memory and resets on restart.
- `reserveMargin(orderId, amount)` exists for internal use/tests and is not exposed as REST.
- History entries are appended per user and returned sorted descending by timestamp.
