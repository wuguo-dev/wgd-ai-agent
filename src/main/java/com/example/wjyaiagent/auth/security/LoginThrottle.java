package com.example.wjyaiagent.auth.security;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Fixed ten-minute window, bounded memory, keyed by the actual peer IP. */
@Component
public class LoginThrottle {
    private record Window(Instant until, int attempts) { }
    private final Map<String, Window> windows = new HashMap<>();

    public synchronized boolean allow(String address) {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> !entry.getValue().until().isAfter(now));
        Window window = windows.get(address);
        if (window == null) {
            if (windows.size() >= 10000) return false;
            window = new Window(now.plusSeconds(600), 0);
        }
        if (window.attempts() >= 10) return false;
        windows.put(address, new Window(window.until(), window.attempts() + 1));
        return true;
    }
}
