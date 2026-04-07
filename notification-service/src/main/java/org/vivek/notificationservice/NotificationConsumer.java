package org.vivek.notificationservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.OrderExpiredEvent;
import org.vivek.commonmodule.model.TradeExecution;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class NotificationConsumer {

    private final Map<String, List<String>> userNotifications = new ConcurrentHashMap<>();
    private final Set<String> processedTradeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final NotificationHandler notificationHandler;

    public NotificationConsumer(NotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
    }

    @KafkaListener(
            topics = "${kafka.consumer.topic}",
            groupId = "notification-group",
            properties = {
                    "spring.json.use.type.headers=false",
                    "spring.json.value.default.type=org.vivek.commonmodule.model.TradeExecution"
            }
    )
    public void consumeTradeExecution(TradeExecution trade) {
        if (trade.getTradeId() != null && processedTradeIds.contains(trade.getTradeId())) {
            log.warn("Trade {} already processed, skipping duplicate event", trade.getTradeId());
            return;
        }

        if (trade.getBuyerId() != null) {
            String buyMsg = String.format("Your order %s executed at INR %s for %s shares of %s",
                    trade.getBuyOrderId(), trade.getExecutedPrice(), trade.getQuantity(), trade.getSymbol());
            saveAndPushNotification(trade.getBuyerId(), buyMsg);
        }

        if (trade.getSellerId() != null) {
            String sellMsg = String.format("Your order %s executed at INR %s for %s shares of %s",
                    trade.getSellOrderId(), trade.getExecutedPrice(), trade.getQuantity(), trade.getSymbol());
            saveAndPushNotification(trade.getSellerId(), sellMsg);
        }

        if (trade.getTradeId() != null) {
            processedTradeIds.add(trade.getTradeId());
        }
    }

    @KafkaListener(
            topics = "order-expired",
            groupId = "notification-group",
            properties = {
                    "spring.json.use.type.headers=false",
                    "spring.json.value.default.type=org.vivek.commonmodule.model.OrderExpiredEvent"
            }
    )
    public void consumeOrderExpired(OrderExpiredEvent event) {
        if (event.getUserId() == null) {
            return;
        }

        String message = String.format(
                "Your GTD order %s for %s expired unfilled at end of day",
                event.getOrderId(),
                event.getSymbol()
        );
        saveAndPushNotification(event.getUserId(), message);
    }

    private void saveAndPushNotification(String userId, String message) {
        userNotifications.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(message);
        log.info("Notification saved for {}: {}", userId, message);

        String payload = String.format("{\"topic\": \"/topic/notifications/%s\", \"message\": \"%s\"}", userId, message);
        notificationHandler.sendMessageToUser(userId, payload);
    }

    public List<String> getNotifications(String userId) {
        return userNotifications.getOrDefault(userId, Collections.emptyList());
    }
}
