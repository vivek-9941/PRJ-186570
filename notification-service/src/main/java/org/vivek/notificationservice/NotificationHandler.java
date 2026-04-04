package org.vivek.notificationservice;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationHandler extends TextWebSocketHandler {

    // Store sessions by userId
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Assume userId is passed as a query param, e.g., /ws?userId=123
        String query = session.getUri() != null ? session.getUri().getQuery() : "";
        String userId = "anonymous";
        if (query != null && query.contains("userId=")) {
            String[] params = query.split("&");
            for (String param : params) {
                if (param.startsWith("userId=")) {
                    userId = param.substring(7);
                    break;
                }
            }
        }
        
        session.getAttributes().put("userId", userId);
        userSessions.computeIfAbsent(userId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(session);
            }
        }
    }

    public void sendMessageToUser(String userId, String message) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage(message));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
