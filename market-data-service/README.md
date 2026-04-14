# Market Data Service

`market-data-service` produces and serves live symbol prices for the platform.
It combines two price sources:
- Real executed trades from Kafka (`trade-executed`) for immediate LTP updates.
- Scheduled simulated ticks for symbols without very recent trades.

It then fan-outs each tick to:
- Kafka topic `market-data`
- WebSocket topic `/topic/prices/{symbol}`
- In-memory latest snapshot and rolling history (for REST reads)

## Service Role In The System

- Runs on HTTP port `8084`.
- Publishes 1-second market ticks for supported symbols.
- Consumes post-trade events to align LTP with actual executions.
- Provides REST endpoints for latest prices and historical ticks.
- Pushes real-time ticks to the dashboard via STOMP/WebSocket.

## Supported Symbols And Baselines

Defined in `PriceSimulator`:
- `INFY`, `TCS`, `RELIANCE`, `HDFC`

Starting prices:
- `INFY=1800.0`
- `TCS=3500.0`
- `RELIANCE=2900.0`
- `HDFC=1650.0`

Base volatility (per 1-second simulation step):
- `INFY=0.004`
- `TCS=0.003`
- `RELIANCE=0.0035`
- `HDFC=0.0045`

Drift guard:
- Simulated prices are clamped to +/-5% around starting price.

## How Market Data Is Produced (Detailed Flow)

Main implementation:
- `src/main/java/org/vivek/marketdataservice/service/PriceSimulator.java`

### 1) Trade-driven updates (authoritative LTP path)

Listener:
- `@KafkaListener(topics = "trade-executed", groupId = "marketdata-group")`

On every `TradeExecution`:
1. Normalize symbol and ignore unsupported symbols.
2. Read previous price from `currentPrices`.
3. Set LTP (`price`) to `trade.executedPrice`.
4. Mark symbol as recently traded (`lastTradeAt = now`).
5. Update cumulative values used for VWAP:
- `cumulativeTradeValue += executedPrice * quantity`
- `cumulativeTradeVolume += quantity`
6. Add trade sample into a 60-second sliding volume window.
7. Build `PriceTick` with:
- `price` = LTP
- `change` and `changePercent` vs previous price
- `volume` = cumulative traded volume for that symbol (rounded to int)
- `vwap` = cumulativeTradeValue / cumulativeTradeVolume
- `timestamp` = now
8. Publish tick to Kafka + WebSocket and persist in latest/history caches.

Result:
- If a real trade happens, UI and downstream consumers see that symbol update immediately.

### 2) Scheduled simulation (gap-filling path)

Scheduler:
- `@Scheduled(fixedRate = 1000)` (`publishTicks`)

Every second, for each supported symbol:
1. If symbol had a trade in the last 5 seconds, skip simulation for that symbol.
2. Otherwise generate next simulated tick via `nextTick(symbol)`.
3. Publish it through the same fan-out path.

Simulation math:
- `change = currentPrice * adjustedVolatility * random(-1..1)`
- `newPrice = clamp(startPrice +/- 5%)`
- `changePercent = (newPrice - currentPrice) / currentPrice * 100`
- `volume = random integer [100, 10000]`
- `vwap = cumulative VWAP if trades exist, else fallback to current simulated price`

### 3) Volatility dampening from recent trade volume

Method: `adjustedVolatility(symbol)`

- Maintains `recentTradeWindow` with trade quantities for last 60 seconds.
- Computes `recentVolume = sum(quantity over last 60s)`.
- Applies multiplier to base volatility:
- If `recentVolume > 1000`, volatility = `base * 0.5` (tighter movement)
- Else volatility = `base * 1.5` (wider movement)

This makes heavily traded symbols move more smoothly than illiquid ones.

## Tick Fan-out And State Stores

Every built tick goes through `publishTick(symbol, tick)`:
- `latestTicks[symbol] = tick`
- append to `history[symbol]` (bounded deque)
- Kafka publish: topic `market-data` with key = symbol
- WebSocket publish: `/topic/prices/{symbol}`

History policy:
- Stores the last `100` ticks per symbol (`MAX_HISTORY_SIZE=100`).

## Data Model

File:
- `src/main/java/org/vivek/marketdataservice/model/PriceTick.java`

Fields:
- `symbol`
- `price`
- `change`
- `changePercent`
- `timestamp`
- `volume`
- `vwap`

## REST API

Controller:
- `src/main/java/org/vivek/marketdataservice/controller/MarketDataController.java`
- Base path: `/api/v1/market-data`

Endpoints:
- `GET /api/v1/market-data`
- Returns latest tick for each supported symbol.

- `GET /api/v1/market-data/{symbol}`
- Returns latest tick for one symbol.
- Returns HTTP 400 for unknown symbols with supported-symbol list.

- `GET /api/v1/market-data/{symbol}/history`
- Returns in-memory tick history (up to last 100 ticks).
- Returns HTTP 400 for unknown symbols.

Example:

```bash
curl http://localhost:8084/api/v1/market-data
curl http://localhost:8084/api/v1/market-data/INFY
curl http://localhost:8084/api/v1/market-data/INFY/history
```

## WebSocket Interface

Config file:
- `src/main/java/org/vivek/marketdataservice/config/WebSocketConfig.java`

Details:
- STOMP endpoint: `/ws/market-data`
- Broker prefix: `/topic`
- Price stream destination pattern: `/topic/prices/{symbol}`
- Allowed origins: `http://localhost:5173`, `http://localhost:3000`

## Kafka Setup

Producer config:
- `src/main/java/org/vivek/marketdataservice/config/KafkaProducerConfig.java`

Topic:
- `market-data` (auto-created bean)
- partitions: `4`
- replicas: `1`

Producer reliability settings:
- `acks=all`
- `retries=3`
- `enable.idempotence=true`

Consumer config (for trade input) in `application.yml`:
- `spring.kafka.consumer.group-id=marketdata-group`
- `spring.kafka.consumer.auto-offset-reset=latest`
- trusted package and default value type for `TradeExecution`

## Configuration

From `src/main/resources/application.yml`:
- `server.port=8084`
- `spring.application.name=market-data-service`
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- consumer and producer serializers configured for JSON messaging

## Build And Run

From repo root:

```bash
mvn -pl market-data-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Notes

- This service is stateful in memory (`currentPrices`, `latestTicks`, `history`, VWAP accumulators); restart resets runtime state to defaults.
- Real trade events dominate the price path by updating LTP immediately and suppressing simulation for 5 seconds per symbol.
- History endpoint is bounded and intended for UI sparkline/time-series preview, not long-term storage.
- Current test coverage only includes Spring context startup (`MarketDataServiceApplicationTests`); there are no unit tests yet for simulation/trade-update logic.
