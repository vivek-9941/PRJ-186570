# Matching Engine Package Overview

This package contains the matching engine implementation used by the `matching-engine` service.
It aligns with the project design in the root `info` file: synchronous pre-trade validation happens upstream, and this service executes trades and emits Kafka post-trade events.

## Package Structure

- `MatchingEngineApplication.java`
  - Spring Boot entrypoint.
- `config/`
  - `KafkaProducerConfig.java`: producer factories/templates for trade and cancellation events; topic declarations.
  - `CorsConfig.java`: CORS rules for `/api/**`.
- `controller/`
  - `MatchingController.java`: REST endpoints for match, cancel, and order book reads.
- `orderbook/`
  - `OrderBookRegistry.java`: per-symbol book registry and bootstrap liquidity seeding.
  - `SymbolOrderBook.java`: core matching and cancellation logic.
  - `BookSnapshot.java`, `OrderBookDepth.java`, `PriceLevel.java`: response/read models.

## Runtime Flow

1. `POST /api/v1/match` receives an order.
2. Controller routes by symbol: `orderBookRegistry.getBook(order.getSymbol()).match(order)`.
3. `SymbolOrderBook` performs price-time matching:
   - BUY consumes best asks while `bestAsk <= buyPrice`
   - SELL consumes best bids while `bestBid >= sellPrice`
4. Each fill creates one `TradeExecution` and is published to Kafka topic `trade-executed`.
5. If unmatched quantity remains:
   - `LIMIT`/`GTD`: remainder rests in the book.
   - `IOC`: remainder is cancelled and `CancellationEvent` is emitted.

## Key Data Structures

- `ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> buyOrders` (descending)
- `ConcurrentSkipListMap<Double, ConcurrentLinkedQueue<Order>> sellOrders` (ascending)

This provides efficient best-price access with FIFO behavior at each price level.

## Public APIs In This Package

- `POST /api/v1/match`
- `DELETE /api/v1/orders/{orderId}` (optional `symbol` query param)
- `GET /api/v1/orderbook/{symbol}`
- `GET /api/v1/orderbook`
- `GET /api/v1/orderbook/{symbol}/depth`

## Kafka Topics Produced

- `trade-executed`
- `order-cancelled`

Producer settings include idempotence, retries, and `acks=all`.

## Notes

- Matching is synchronized per symbol book for consistency.
- State is in-memory and resets on restart.
- Without `symbol` in cancel endpoint, cancellation scans all books.
