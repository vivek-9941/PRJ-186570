package org.vivek.analyticsservice;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DltMonitorTest {

    @Test
    void dltMessagesAreCountedPerTopic() {
        DltMonitor monitor = new DltMonitor(new SimpleMeterRegistry());
        ConsumerRecord<String, Object> record =
                new ConsumerRecord<>("trade-executed.DLT", 0, 12L, "key-1", Map.of("tradeId", "TRD-1"));
        record.headers().add("X-Exception-Message", "boom".getBytes(StandardCharsets.UTF_8));

        monitor.consumeDlt(record);

        assertEquals(1L, monitor.snapshot().get("trade-executed.DLT").get("count"));
        assertEquals("boom", monitor.snapshot().get("trade-executed.DLT").get("lastErrorMessage"));
    }
}
