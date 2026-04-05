package org.vivek.riskservice.service;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.vivek.trade.risk.grpc.RiskServiceGrpc;
import org.vivek.trade.risk.grpc.ValidationRequest;
import org.vivek.trade.risk.grpc.ValidationResponse;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@GrpcService
@Slf4j
public class RiskServiceImpl extends RiskServiceGrpc.RiskServiceImplBase {

    // ── Risk config (hardcoded constants) ──────────────────────────────
    public static final double MAX_POSITION_LIMIT = 10_000;        // shares per symbol per user
    public static final double MAX_ORDER_VALUE = 500_000.0;        // ₹5 lakhs per order
    public static final double MAX_DAILY_LOSS = -25_000.0;         // ₹25,000 max loss per day
    public static final double MAX_EXPOSURE_MULTIPLIER = 5.0;      // total open value ≤ 5× balance

    // ── In-memory data stores ──────────────────────────────────────────
    // userId → (symbol → current quantity held)
    private final Map<String, Map<String, Double>> userPositions = new ConcurrentHashMap<>();

    // userId → today's realized PnL in rupees
    private final Map<String, Double> userDailyPnL = new ConcurrentHashMap<>();

    @Value("${grpc.server.port:9091}")
    private int port;

    @PostConstruct
    public void init() {
        // Pre-populate positions
        Map<String, Double> u1Positions = new ConcurrentHashMap<>();
        u1Positions.put("INFY", 8000.0);
        u1Positions.put("TCS", 500.0);
        u1Positions.put("RELIANCE", 200.0);
        userPositions.put("U1", u1Positions);

        Map<String, Double> u2Positions = new ConcurrentHashMap<>();
        u2Positions.put("INFY", 0.0);
        u2Positions.put("TCS", 1000.0);
        userPositions.put("U2", u2Positions);

        userPositions.put("U3", new ConcurrentHashMap<>());

        // Pre-populate daily PnL
        userDailyPnL.put("U1", -8000.0);
        userDailyPnL.put("U2", 5000.0);
        userDailyPnL.put("U3", 0.0);

        log.info("RiskService gRPC server initialized on port: {}. "
                + "Loaded {} user positions, {} daily PnL entries",
                port, userPositions.size(), userDailyPnL.size());
    }

    // ── gRPC validate ──────────────────────────────────────────────────

    @Override
    public void validate(ValidationRequest request, StreamObserver<ValidationResponse> responseObserver) {
        log.info("Received Risk Validation request for order: {}", request.getOrderId());
        long start = System.currentTimeMillis();

        String userId = request.getUserId();
        String symbol = request.getSymbol();
        String side = request.getSide();
        double quantity = request.getQuantity();
        double price = request.getPrice();

        // ─── Check 1 — Max order value ─────────────────────────────────
        double orderValue = quantity * price;
        if (orderValue > MAX_ORDER_VALUE) {
            String reason = String.format(
                    "ORDER_VALUE_EXCEEDED: order value ₹%.2f exceeds limit ₹%.2f",
                    orderValue, MAX_ORDER_VALUE);
            log.debug("Risk check [ORDER_VALUE] userId={} symbol={} result=FAIL reason=\"{}\"",
                    userId, symbol, reason);
            respond(responseObserver, false, reason, start);
            return;
        }
        log.debug("Risk check [ORDER_VALUE] userId={} symbol={} result=PASS reason=\"order value ₹{} within limit\"",
                userId, symbol, orderValue);

        // ─── Check 2 — Daily loss limit ────────────────────────────────
        double currentPnL = userDailyPnL.getOrDefault(userId, 0.0);
        if (currentPnL <= MAX_DAILY_LOSS) {
            String reason = String.format(
                    "DAILY_LOSS_LIMIT_REACHED: daily PnL ₹%.2f at or below limit ₹%.2f",
                    currentPnL, MAX_DAILY_LOSS);
            log.debug("Risk check [DAILY_LOSS] userId={} symbol={} result=FAIL reason=\"{}\"",
                    userId, symbol, reason);
            respond(responseObserver, false, reason, start);
            return;
        }
        log.debug("Risk check [DAILY_LOSS] userId={} symbol={} result=PASS reason=\"daily PnL ₹{} within limit\"",
                userId, symbol, currentPnL);

        // ─── Check 3 — Position limit (BUY orders only) ────────────────
        if ("BUY".equalsIgnoreCase(side)) {
            double currentPosition = userPositions
                    .getOrDefault(userId, Collections.emptyMap())
                    .getOrDefault(symbol, 0.0);
            double projectedPosition = currentPosition + quantity;
            if (projectedPosition > MAX_POSITION_LIMIT) {
                String reason = String.format(
                        "POSITION_LIMIT_EXCEEDED: projected position %.0f exceeds limit %.0f for %s",
                        projectedPosition, MAX_POSITION_LIMIT, symbol);
                log.debug("Risk check [POSITION_LIMIT] userId={} symbol={} result=FAIL reason=\"{}\"",
                        userId, symbol, reason);
                respond(responseObserver, false, reason, start);
                return;
            }
            log.debug("Risk check [POSITION_LIMIT] userId={} symbol={} result=PASS reason=\"projected {} within limit\"",
                    userId, symbol, projectedPosition);
        }

        // ─── Check 4 — Short sell check (SELL orders only) ─────────────
        if ("SELL".equalsIgnoreCase(side)) {
            double currentPosition = userPositions
                    .getOrDefault(userId, Collections.emptyMap())
                    .getOrDefault(symbol, 0.0);
            if (quantity > currentPosition) {
                String reason = String.format(
                        "INSUFFICIENT_POSITION: trying to sell %.0f but only hold %.0f shares of %s",
                        quantity, currentPosition, symbol);
                log.debug("Risk check [SHORT_SELL] userId={} symbol={} result=FAIL reason=\"{}\"",
                        userId, symbol, reason);
                respond(responseObserver, false, reason, start);
                return;
            }
            log.debug("Risk check [SHORT_SELL] userId={} symbol={} result=PASS reason=\"holding {} ≥ sell qty {}\"",
                    userId, symbol, currentPosition, quantity);
        }

        // ─── All checks passed ─────────────────────────────────────────
        log.debug("Risk check [ALL] userId={} symbol={} result=PASS reason=\"all checks passed\"",
                userId, symbol);
        respond(responseObserver, true, "All risk checks passed", start);
    }

    // ── Public accessors (used by REST controller & Kafka listener) ────

    public Map<String, Double> getPositions(String userId) {
        return userPositions.getOrDefault(userId, Collections.emptyMap());
    }

    public void updatePosition(String userId, String symbol, double quantity) {
        userPositions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(symbol, quantity);
        log.info("Position updated: userId={} symbol={} quantity={}", userId, symbol, quantity);
    }

    public void adjustPosition(String userId, String symbol, double delta) {
        userPositions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .merge(symbol, delta, Double::sum);
        log.info("Position adjusted: userId={} symbol={} delta={}", userId, symbol, delta);
    }

    public Map<String, Object> getRiskConfig() {
        return Map.of(
                "MAX_POSITION_LIMIT", MAX_POSITION_LIMIT,
                "MAX_ORDER_VALUE", MAX_ORDER_VALUE,
                "MAX_DAILY_LOSS", MAX_DAILY_LOSS,
                "MAX_EXPOSURE_MULTIPLIER", MAX_EXPOSURE_MULTIPLIER
        );
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private void respond(StreamObserver<ValidationResponse> observer,
                         boolean success, String reason, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        ValidationResponse response = ValidationResponse.newBuilder()
                .setSuccess(success)
                .setServiceId("risk-service")
                .setReason(reason)
                .setLatencyMs(elapsed)
                .build();
        observer.onNext(response);
        observer.onCompleted();
    }
}
