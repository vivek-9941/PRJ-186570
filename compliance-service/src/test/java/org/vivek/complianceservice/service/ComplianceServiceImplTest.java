package org.vivek.complianceservice.service;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.vivek.trade.compliance.grpc.ValidationRequest;
import org.vivek.trade.compliance.grpc.ValidationResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplianceServiceImplTest {

    private ComplianceServiceImpl complianceService;

    @BeforeEach
    void setUp() {
        complianceService = new ComplianceServiceImpl();
        ReflectionTestUtils.setField(complianceService, "bypassMarketHours", true);
        ReflectionTestUtils.setField(complianceService, "priceBandPercent", 0.20d);
        ReflectionTestUtils.setField(complianceService, "duplicateWindowMs", 1000L);
        complianceService.init();
    }

    @Test
    void validateRejectsWhenMarketClosedAndBypassDisabled() {
        ReflectionTestUtils.setField(complianceService, "bypassMarketHours", false);
        complianceService.setClock(Clock.fixed(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Kolkata")));

        ValidationResponse response = validate(request("ORD-1", "U1", "INFY", "BUY", 1820.0d));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("MARKET_CLOSED"));
        assertTrue(response.getReason().contains("07:30"));
    }

    @Test
    void validateRejectsBannedSymbol() {
        ValidationResponse response = validate(request("ORD-2", "U1", "YESBANK", "BUY", 10.0d));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("SYMBOL_BANNED"));
    }

    @Test
    void validateRejectsPriceOutsideBand() {
        ValidationResponse response = validate(request("ORD-3", "U1", "INFY", "BUY", 2500.0d));

        assertFalse(response.getSuccess());
        assertTrue(response.getReason().contains("PRICE_ABOVE_UPPER_BAND"));
    }

    @Test
    void validateRejectsDuplicateOrderInsideWindow() {
        complianceService.setClock(Clock.fixed(Instant.parse("2026-04-05T05:00:00Z"), ZoneId.of("Asia/Kolkata")));
        validate(request("ORD-4", "U1", "INFY", "BUY", 1820.0d));

        ValidationResponse duplicate = validate(request("ORD-5", "U1", "INFY", "BUY", 1820.0d));

        assertFalse(duplicate.getSuccess());
        assertTrue(duplicate.getReason().contains("DUPLICATE_ORDER"));
    }

    @Test
    void validateAllowsSameOrderAfterWindowExpires() {
        complianceService.setClock(Clock.fixed(Instant.parse("2026-04-05T05:00:00Z"), ZoneId.of("Asia/Kolkata")));
        validate(request("ORD-6", "U1", "INFY", "BUY", 1820.0d));

        complianceService.setClock(Clock.fixed(Instant.parse("2026-04-05T05:00:02Z"), ZoneId.of("Asia/Kolkata")));
        ValidationResponse response = validate(request("ORD-7", "U1", "INFY", "BUY", 1820.0d));

        assertTrue(response.getSuccess());
        assertTrue(response.getReason().contains("COMPLIANT"));
    }

    @Test
    void bannedSymbolEndpointsHelpersUpdateState() {
        complianceService.addBannedSymbol("SBIN");
        assertTrue(complianceService.getBannedSymbols().contains("SBIN"));

        complianceService.removeBannedSymbol("SBIN");
        assertFalse(complianceService.getBannedSymbols().contains("SBIN"));
    }

    @Test
    void refreshPreviousClosePricesKeepsBandsAvailable() {
        double before = complianceService.getBands().get("INFY").previousClose();

        complianceService.refreshPreviousClosePrices();

        double after = complianceService.getBands().get("INFY").previousClose();
        assertNotNull(complianceService.getBands().get("INFY"));
        assertTrue(after > 0.0d);
        assertTrue(Math.abs(after - before) <= before * 0.01d);
    }

    private ValidationRequest request(String orderId, String userId, String symbol, String side, double price) {
        return ValidationRequest.newBuilder()
                .setOrderId(orderId)
                .setUserId(userId)
                .setSymbol(symbol)
                .setSide(side)
                .setQuantity(10.0d)
                .setPrice(price)
                .build();
    }

    private ValidationResponse validate(ValidationRequest request) {
        TestObserver observer = new TestObserver();
        complianceService.validate(request, observer);
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
