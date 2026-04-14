# Compliance Service

`compliance-service` is the pre-trade regulatory validator in the order pipeline.
It provides a gRPC `Validate` API (used by order-service DAG execution) and helper REST endpoints for operational visibility and simulation.

## Service Role In The System

- Pre-trade synchronous validation over gRPC (as defined in `info`).
- Enforces market/compliance constraints before order approval.
- Exposes REST helpers to inspect price bands and manage banned symbols.
- HTTP (actuator + REST): `8093`
- gRPC server: `9093`

## gRPC Contract

Proto file: `../proto/compliance.proto`

- Service: `trade.compliance.ComplianceService`
- RPC: `Validate(ValidationRequest) -> ValidationResponse`

`ValidationRequest` fields:
- `order_id`
- `user_id`
- `symbol`
- `side`
- `quantity`
- `price`

`ValidationResponse` fields:
- `success`
- `service_id`
- `reason`
- `latency_ms`

## Current Implementation

Main implementation class:
- `src/main/java/org/vivek/complianceservice/service/ComplianceServiceImpl.java`

Implemented checks (in order):
- Market hours check (IST 09:15-15:30) when `compliance.bypass-market-hours=false`
- Banned symbol check (in-memory ban list)
- Price band check against previous close using `compliance.price-band-percent`
- Duplicate order detection within `compliance.duplicate-window-ms`

Success reason format:
- `COMPLIANT: market open, symbol active, price within band ..., no duplicate detected`

Failure reason prefixes:
- `MARKET_CLOSED`
- `SYMBOL_BANNED`
- `PRICE_ABOVE_UPPER_BAND`
- `PRICE_BELOW_LOWER_BAND`
- `DUPLICATE_ORDER`

In-memory bootstrap data (`@PostConstruct`):
- Banned symbols: `YESBANK`, `SUZLON`
- Previous close map includes: `INFY`, `TCS`, `RELIANCE`, `HDFC`

Scheduled behavior:
- Every 60 seconds, previous close prices drift by +/-0.5% (`refreshPreviousClosePrices`) to simulate live band movement.

## REST Endpoints

Controller:
- `src/main/java/org/vivek/complianceservice/controller/ComplianceController.java`
- Base path: `/api/v1/compliance`

Endpoints:
- `GET /bands` -> map of symbol to `{symbol, previousClose, lowerBand, upperBand}`
- `GET /banned` -> banned symbol set
- `POST /banned/{symbol}` -> add symbol to ban list
- `DELETE /banned/{symbol}` -> remove symbol from ban list

Example calls:

```bash
curl http://localhost:8093/api/v1/compliance/bands
curl http://localhost:8093/api/v1/compliance/banned
curl -X POST http://localhost:8093/api/v1/compliance/banned/SBIN
curl -X DELETE http://localhost:8093/api/v1/compliance/banned/SBIN
```

## Configuration

From `src/main/resources/application.yml`:

- `server.port=8093`
- `grpc.server.port=9093`
- `spring.application.name=compliance-service`
- `compliance.bypass-market-hours=true`
- `compliance.price-band-percent=0.20`
- `compliance.duplicate-window-ms=1000`
- `management.endpoints.web.exposure.include=health`

## Build And Run

From repository root:

```bash
mvn -pl compliance-service -am spring-boot:run
```

From module directory:

```bash
mvn spring-boot:run
```

## Tests

Run:

```bash
mvn -pl compliance-service test
```

Existing unit tests cover:
- Market closed rejection when bypass disabled
- Banned symbol rejection
- Price-band rejection
- Duplicate-order rejection within window
- Acceptance after duplicate window expiry
- Ban list add/remove helpers
- Scheduled price refresh keeps bands available

Test class:
- `src/test/java/org/vivek/complianceservice/service/ComplianceServiceImplTest.java`

## Notes

- State is in-memory only (banned symbols, price map, recent fingerprints).
- Data resets on service restart.
- Duplicate detection compares `(userId, symbol, side, exact price)` within window.
- Market-hour evaluation uses IST timezone (`Asia/Kolkata`).
