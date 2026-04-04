package org.vivek.notificationservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.vivek.commonmodule.model.TradeExecution;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class NotificationConsumer {

    private final Map<String, List<String>> userNotifications = new ConcurrentHashMap<>();
    private final NotificationHandler notificationHandler;

    public NotificationConsumer(NotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
    }

    @KafkaListener(topics = "${kafka.consumer.topic}", groupId = "notification-group")
    public void consumeTradeExecution(TradeExecution trade) {
        
        if (trade.getBuyerId() != null) {
            String buyMsg = String.format("Your order %s executed at ₹%s for %s shares of %s", 
                    trade.getBuyOrderId(), trade.getExecutedPrice(), trade.getQuantity(), trade.getSymbol());
            saveAndPushNotification(trade.getBuyerId(), buyMsg);
        }

        if (trade.getSellerId() != null) {
            String sellMsg = String.format("Your order %s executed at ₹%s for %s shares of %s", 
                    trade.getSellOrderId(), trade.getExecutedPrice(), trade.getQuantity(), trade.getSymbol());
            saveAndPushNotification(trade.getSellerId(), sellMsg);
        }
    }

    private void saveAndPushNotification(String userId, String message) {
        userNotifications.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(message);
        log.info("Notification saved for {}: {}", userId, message);
        
        // Push over WebSocket
        String payload = String.format("{\"topic\": \"/topic/notifications/%s\", \"message\": \"%s\"}", userId, message);
        notificationHandler.sendMessageToUser(userId, payload);
    }

    public List<String> getNotifications(String userId) {
        return userNotifications.getOrDefault(userId, Collections.emptyList());
    }
}
