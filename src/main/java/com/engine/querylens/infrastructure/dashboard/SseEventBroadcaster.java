package com.engine.querylens.infrastructure.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active Server-Sent Events (SSE) connections and broadcasts events to connected browsers.
 */
public class SseEventBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(SseEventBroadcaster.class);

    private final Set<OutputStream> activeClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void addClient(OutputStream clientStream) {
        activeClients.add(clientStream);
        log.debug("New SSE client subscribed. Total active clients: {}", activeClients.size());
    }

    public void removeClient(OutputStream clientStream) {
        activeClients.remove(clientStream);
        log.debug("SSE client disconnected. Total active clients: {}", activeClients.size());
    }

    public synchronized void broadcast(String eventType, Object payload) {
        if (activeClients.isEmpty()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            String message = "event: " + eventType + "\ndata: " + json + "\n\n";
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

            activeClients.removeIf(client -> {
                try {
                    client.write(bytes);
                    client.flush();
                    return false;
                } catch (IOException e) {
                    log.debug("Removing closed SSE client connection");
                    return true;
                }
            });
        } catch (Exception e) {
            log.error("Failed to broadcast SSE event", e);
        }
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }
}
