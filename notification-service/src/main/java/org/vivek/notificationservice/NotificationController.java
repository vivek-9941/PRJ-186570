package org.vivek.notificationservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationConsumer notificationConsumer;

    public NotificationController(NotificationConsumer notificationConsumer) {
        this.notificationConsumer = notificationConsumer;
    }

    @GetMapping("/{userId}")
    public List<String> getUserNotifications(@PathVariable String userId) {
        return notificationConsumer.getNotifications(userId);
    }
}
