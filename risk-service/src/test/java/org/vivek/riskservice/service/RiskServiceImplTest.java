package org.vivek.riskservice.service;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.vivek.trade.risk.grpc.ValidationRequest;
import org.vivek.trade.risk.grpc.ValidationResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskServiceImplTest {

    private RiskServiceImpl riskService;

    @BeforeEach
    void setUp() {
        riskService = new RiskServiceImpl();
        ReflectionTestUtils.setField(riskService, "port", 9091);
        riskService.init();
    }

    @Test
    void validateRejectsWhenOrderValueExceedsLimit() {
        ValidationResponse response = validate(request("ORD-1", "U1", "INFY", "BUY", 1_000, 600.0));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("ORDER_VALUE_EXCEEDED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateRejectsWhenDailyLossLimitReached() {
        Map<String, Double> userDailyPnL = (Map<String, Double>) ReflectionTestUtils.getField(riskService, "userDailyPnL");
        assertNotNull(userDailyPnL);
        userDailyPnL.put("U1", RiskServiceImpl.MAX_DAILY_LOSS);

        ValidationResponse response = validate(request("ORD-2", "U1", "INFY", "BUY", 10, 100.0));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("DAILY_LOSS_LIMIT_REACHED"));
    }

    @Test
    void validateRejectsBuyWhenProjectedPositionExceedsLimit() {
        ValidationResponse response = validate(request("ORD-3", "U1", "INFY", "BUY", 2_001, 100.0));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("POSITION_LIMIT_EXCEEDED"));
    }

    @Test
    void validateRejectsSellWhenInsufficientPosition() {
        ValidationResponse response = validate(request("ORD-4", "U2", "INFY", "SELL", 10, 100.0));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("INSUFFICIENT_POSITION"));
    }

    @Test
    void validatePassesWhenAllChecksPass() {
        ValidationResponse response = validate(request("ORD-5", "U3", "INFY", "BUY", 100, 100.0));

        assertTrue(response.getSuccess());
        assertEquals("All risk checks passed", response.getReason());
        assertTrue(response.getLatencyMs() >= 0);
    }

    @Test
    void updateAndAdjustPositionMutateUserPositions() {
        riskService.updatePosition("U9", "TCS", 150.0);
        riskService.adjustPosition("U9", "TCS", -50.0);

        assertEquals(100.0, riskService.getPositions("U9").get("TCS"));
    }

    @Test
    void getRiskConfigReturnsConfiguredLimits() {
        Map<String, Object> config = riskService.getRiskConfig();

        assertEquals(RiskServiceImpl.MAX_POSITION_LIMIT, config.get("MAX_POSITION_LIMIT"));
        assertEquals(RiskServiceImpl.MAX_ORDER_VALUE, config.get("MAX_ORDER_VALUE"));
        assertEquals(RiskServiceImpl.MAX_DAILY_LOSS, config.get("MAX_DAILY_LOSS"));
        assertEquals(RiskServiceImpl.MAX_EXPOSURE_MULTIPLIER, config.get("MAX_EXPOSURE_MULTIPLIER"));
    }

    private ValidationRequest request(String orderId, String userId, String symbol, String side, double quantity, double price) {
        return ValidationRequest.newBuilder()
                .setOrderId(orderId)
                .setUserId(userId)
                .setSymbol(symbol)
                .setSide(side)
                .setQuantity(quantity)
                .setPrice(price)
                .build();
    }

    private ValidationResponse validate(ValidationRequest request) {
        TestObserver observer = new TestObserver();
        riskService.validate(request, observer);
        assertNotNull(observer.response);
        return observer.response;
    }

    private static final class TestObserver implements StreamObserver<ValidationResponse> {
        private ValidationResponse response;
        private Throwable error;

        @Override
        public void onNext(ValidationResponse value) {
            this.response = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            if (error != null) {
                throw new AssertionError("Unexpected observer error", error);
            }
        }
    }
}
