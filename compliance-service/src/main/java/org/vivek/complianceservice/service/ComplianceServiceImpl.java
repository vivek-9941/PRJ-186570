package org.vivek.complianceservice.service;

import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.vivek.trade.compliance.grpc.ComplianceServiceGrpc;
import org.vivek.trade.compliance.grpc.ValidationRequest;
import org.vivek.trade.compliance.grpc.ValidationResponse;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@GrpcService
@Slf4j
public class ComplianceServiceImpl extends ComplianceServiceGrpc.ComplianceServiceImplBase {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final Set<String> bannedSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, Double> previousClosePrices = new ConcurrentHashMap<>();
    private final Map<String, List<OrderFingerprint>> recentOrders = new ConcurrentHashMap<>();

    @Value("${grpc.server.port:9093}")
    private int port;

    @Value("${compliance.bypass-market-hours:true}")
    private boolean bypassMarketHours;

    @Value("${compliance.price-band-percent:0.20}")
    private double priceBandPercent;

    @Value("${compliance.duplicate-window-ms:1000}")
    private long duplicateWindowMs;

    private Clock clock = Clock.system(IST_ZONE);

    @PostConstruct
    public void init() {
        bannedSymbols.clear();
        bannedSymbols.add("YESBANK");
        bannedSymbols.add("SUZLON");

        previousClosePrices.clear();
        previousClosePrices.put("INFY", 1820.0d);
        previousClosePrices.put("TCS", 3480.0d);
        previousClosePrices.put("RELIANCE", 2910.0d);
        previousClosePrices.put("HDFC", 1640.0d);

        recentOrders.clear();

        log.info("ComplianceService initialized on port {} with {} price bands and {} banned symbols",
                port, previousClosePrices.size(), bannedSymbols.size());
    }

    @Override
    public void validate(ValidationRequest request, StreamObserver<ValidationResponse> responseObserver) {
        long start = System.currentTimeMillis();

        try {
            ValidationOutcome outcome = validateRequest(request);

            responseObserver.onNext(ValidationResponse.newBuilder()
                    .setSuccess(outcome.success())
                    .setServiceId("compliance-service")
                    .setReason(outcome.reason())
                    .setLatencyMs(System.currentTimeMillis() - start)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Compliance validation failed unexpectedly for order {}", request.getOrderId(), ex);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Compliance validation failed")
                    .withCause(ex)
                    .asRuntimeException());
        }
    }

    public synchronized Map<String, BandSnapshot> getBands() {
        Map<String, BandSnapshot> bands = new TreeMap<>();
        previousClosePrices.forEach((symbol, previousClose) -> {
            bands.put(symbol, bandSnapshot(symbol, previousClose));
        });
        return bands;
    }

    public synchronized Set<String> getBannedSymbols() {
        return new TreeSet<>(bannedSymbols);
    }

    public synchronized void addBannedSymbol(String symbol) {
        bannedSymbols.add(normalizeSymbol(symbol));
    }

    public synchronized void removeBannedSymbol(String symbol) {
        bannedSymbols.remove(normalizeSymbol(symbol));
    }

    @Scheduled(fixedRate = 60000)
    public synchronized void refreshPreviousClosePrices() {
        previousClosePrices.replaceAll((symbol, previousClose) -> {
            double drift = ThreadLocalRandom.current().nextDouble(-0.005d, 0.005d);
            double updated = previousClose * (1.0d + drift);
            return round2(updated);
        });
        log.debug("Refreshed compliance bands with random drift: {}", previousClosePrices);
    }

    synchronized ValidationOutcome validateRequest(ValidationRequest request) {
        String userId = request.getUserId();
        String symbol = normalizeSymbol(request.getSymbol());
        String side = normalizeSide(request.getSide());
        double price = request.getPrice();

        ZonedDateTime nowIst = ZonedDateTime.now(clock).withZoneSameInstant(IST_ZONE);
        LocalTime currentTime = nowIst.toLocalTime();
        if (!bypassMarketHours && !isMarketOpen(nowIst)) {
            return new ValidationOutcome(false, String.format(Locale.US,
                    "MARKET_CLOSED: market hours are 09:15 to 15:30 IST, current time is %s IST",
                    currentTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))));
        }

        if (bannedSymbols.contains(symbol)) {
            return new ValidationOutcome(false, String.format(Locale.US,
                    "SYMBOL_BANNED: %s is currently suspended or in F&O ban period",
                    symbol));
        }

        Double previousClose = previousClosePrices.get(symbol);
        double lowerBand = 0.0d;
        double upperBand = 0.0d;
        if (previousClose != null) {
            lowerBand = round2(previousClose * (1.0d - priceBandPercent));
            upperBand = round2(previousClose * (1.0d + priceBandPercent));

            if (price > upperBand) {
                return new ValidationOutcome(false, String.format(Locale.US,
                        "PRICE_ABOVE_UPPER_BAND: order price %s exceeds upper circuit %s (prev close %s + %.0f%%)",
                        inr(price), inr(upperBand), inr(previousClose), priceBandPercent * 100.0d));
            }
            if (price < lowerBand) {
                return new ValidationOutcome(false, String.format(Locale.US,
                        "PRICE_BELOW_LOWER_BAND: order price %s below lower circuit %s (prev close %s - %.0f%%)",
                        inr(price), inr(lowerBand), inr(previousClose), priceBandPercent * 100.0d));
            }
        }

        long nowMs = clock.millis();
        List<OrderFingerprint> recentList = recentOrders.computeIfAbsent(userId, ignored -> new ArrayList<>());
        recentList.removeIf(fingerprint -> nowMs - fingerprint.timestamp() >= duplicateWindowMs);

        boolean isDuplicate = recentList.stream().anyMatch(fingerprint ->
                fingerprint.symbol().equals(symbol)
                        && fingerprint.side().equals(side)
                        && Double.compare(fingerprint.price(), price) == 0
                        && (nowMs - fingerprint.timestamp()) < duplicateWindowMs
        );

        if (isDuplicate) {
            return new ValidationOutcome(false, String.format(Locale.US,
                    "DUPLICATE_ORDER: identical order for %s %s @ %s placed within the last 1 second",
                    symbol, side, inr(price)));
        }

        recentList.add(new OrderFingerprint(userId, symbol, side, price, nowMs));

        String bandText = previousClose != null
                ? String.format(Locale.US, "%s-%s", inr(lowerBand), inr(upperBand))
                : "N/A";

        return new ValidationOutcome(true, String.format(Locale.US,
                "COMPLIANT: market open, symbol active, price within band %s, no duplicate detected",
                bandText));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private boolean isMarketOpen(ZonedDateTime now) {
        int hour = now.getHour();
        int minute = now.getMinute();
        return (hour == 9 && minute >= 15)
                || (hour > 9 && hour < 15)
                || (hour == 15 && minute <= 30);
    }

    private BandSnapshot bandSnapshot(String symbol, double previousClose) {
        double lowerBand = round2(previousClose * (1.0d - priceBandPercent));
        double upperBand = round2(previousClose * (1.0d + priceBandPercent));
        return new BandSnapshot(symbol, round2(previousClose), lowerBand, upperBand);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        return side == null ? "" : side.trim().toUpperCase(Locale.ROOT);
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String inr(double amount) {
        return String.format(Locale.US, "₹%.2f", amount);
    }

    public record BandSnapshot(
            String symbol,
            double previousClose,
            double lowerBand,
            double upperBand
    ) {
    }

    private record OrderFingerprint(
            String userId,
            String symbol,
            String side,
            double price,
            long timestamp
    ) {
    }

    private record ValidationOutcome(boolean success, String reason) {
    }
}
