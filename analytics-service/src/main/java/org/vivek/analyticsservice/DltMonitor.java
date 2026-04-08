package org.vivek.analyticsservice;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DltMonitor {

    private final MeterRegistry meterRegistry;
    private final Map<String, DltTopicStatus> topicStatus = new ConcurrentHashMap<>();

    public DltMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = {"trade-executed.DLT", "order-cancelled.DLT"},
            groupId = "analytics-dlt-monitor",
            properties = {
                    "spring.json.use.type.headers=false",
                    "spring.json.value.default.type=java.util.LinkedHashMap"
            }
    )
    public void consumeDlt(ConsumerRecord<String, Object> record) {
        Map<String, String> headers = toHeaderMap(record);
        String topic = record.topic();
        String lastError = headers.getOrDefault("X-Exception-Message", "unknown");

        topicStatus.compute(topic, (key, current) -> {
            DltTopicStatus next = current == null ? new DltTopicStatus() : current;
            next.increment();
            next.setLastErrorMessage(lastError);
            return next;
        });

        Counter.builder("dlq.messages")
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();

        log.error("DLT message received topic={} partition={} offset={} key={} headers={} payload={}",
                topic, record.partition(), record.offset(), record.key(), headers, record.value());
    }

    public Map<String, Map<String, Object>> snapshot() {
        return topicStatus.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Map.of(
                                "count", entry.getValue().getCount(),
                                "lastErrorMessage", entry.getValue().getLastErrorMessage()
                        ),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, String> toHeaderMap(ConsumerRecord<String, Object> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private static final class DltTopicStatus {
        private long count;
        private String lastErrorMessage;

        public long getCount() {
            return count;
        }

        public void increment() {
            this.count++;
        }

        public String getLastErrorMessage() {
            return lastErrorMessage;
        }

        public void setLastErrorMessage(String lastErrorMessage) {
            this.lastErrorMessage = lastErrorMessage;
        }
    }
}
