package com.rpgjr.mirana.utils;

import com.rpgjr.mirana.exception.ApiException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** In-memory sliding window. Good for one instance; move to a shared store before scaling out. */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String key, int max, Duration window) {
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many attempts. Wait a few minutes and try again.");
            }
            q.addLast(now);
        }
        if (hits.size() > 20_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    public void reset() {
        hits.clear();
    }
}
