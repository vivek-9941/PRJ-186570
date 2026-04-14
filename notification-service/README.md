# Notification Service

`notification-service` is a post-trade/event consumer that generates user-facing messages and delivers them via:
- REST read API (notification history per user)
- WebSocket push (real-time delivery)

## Service Role

- Consumes `trade-executed` events and notifies buyer/seller.
- Consumes `order-expired` events and notifies users about GTD expiry.
- Stores notifications in memory by `userId`.
- Pushes notifications to connected WebSocket clients for that user.

## Ports and Endpoints

- HTTP: `8083`
- REST base path: `/api/v1/notifications`
- WebSocket endpoint: `/ws`

## Current Implementation

Main consumer:
- `src/main/java/org/vivek/notificationservice/NotificationConsumer.java`

In-memory stores:
- `userNotifications: Map<String, List<String>>`
- `processedTradeIds: Set<String>` for trade idempotency

### Kafka Consumer Flows

1) Trade execution notifications
- Listener topic: `${kafka.consumer.topic}` (default `trade-executed`)
- Group: `notification-group`
- Behavior: skips duplicate `tradeId`, creates buyer/seller execution messages, stores them, and pushes to user WebSocket sessions

Message format:
- `Your order {orderId} executed at INR {price} for {qty} shares of {symbol}`

2) GTD expiry notifications
- Listener topic: `order-expired`
- Group: `notification-group`
- Behavior: if `userId` exists, creates expiry message, stores it, and pushes to WebSocket

Message format:
- `Your GTD order {orderId} for {symbol} expired unfilled at end of day`

## WebSocket Delivery Model

Handler:
- `src/main/java/org/vivek/notificationservice/NotificationHandler.java`

Config:
- `src/main/java/org/vivek/notificationservice/WebSocketConfig.java`

How it works:
- WebSocket clients connect to `/ws`.
- `NotificationHandler` extracts `userId` from query string (example: `/ws?userId=U1`).
- Sessions are stored by user in `userSessions`.
- On notification, `sendMessageToUser(userId, payload)` sends to all open sessions for that user.

Current pushed payload format (stringified JSON):
- `{"topic": "/topic/notifications/{userId}", "message": "..."}`

Allowed origins:
- `http://localhost:5173`
- `http://localhost:3000`

## REST API

Controller:
- `src/main/java/org/vivek/notificationservice/NotificationController.java`

Endpoint:
- `GET /api/v1/notifications/{userId}` -> `List<String>`

Example:

```bash
curl http://localhost:8083/api/v1/notifications/U1
```

## Kafka Reliability (DLT)

Config class:
- `src/main/java/org/vivek/notificationservice/KafkaConsumerConfig.java`

Implemented behavior:
- Consumer with JSON deserialization and type headers disabled
- Retry sequence via `DefaultErrorHandler`: `1s`, `3s`, `10s`
- After retries, failed records routed to `<original-topic>.DLT`
- Adds DLQ headers: `X-Exception-Message`, `X-Exception-Stacktrace` (truncated), `X-Original-Offset`, `X-Failed-At`

## Configuration

From `src/main/resources/application.yml`:
- `server.port=8083`
- `spring.application.name=notification-service`
- `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
- `spring.kafka.consumer.group-id=notification-group`
- `spring.kafka.consumer.auto-offset-reset=earliest`
- `kafka.consumer.topic=trade-executed`

CORS for REST (`/api/**`) is configured in:
- `src/main/java/org/vivek/notificationservice/config/CorsConfig.java`

## Build and Run

From repo root:

```bash
mvn -pl notification-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl notification-service test
```

Current tests cover:
- GTD expiry message content (`consumeOrderExpiredStoresExpectedGtdMessage`)
- Duplicate trade id ignored (`duplicateTradeIsIgnored`)
- Spring context startup

Test files:
- `src/test/java/org/vivek/notificationservice/NotificationConsumerTest.java`
- `src/test/java/org/vivek/notificationservice/NotificationServiceApplicationTests.java`

## Notes

- Notification history and WebSocket session state are in-memory only and reset on restart.
- WebSocket delivery depends on client passing `userId` query param.
- Trade idempotency currently applies only to `trade-executed` (not `order-expired`).
