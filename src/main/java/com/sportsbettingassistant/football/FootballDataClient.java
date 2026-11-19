package com.sportsbettingassistant.football;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.sportsbettingassistant.json.Json;

/**
 * Read-only football-data.org client. Calls are queued so they never go over the plan's limit
 * (free plan: 10 calls/minute), and a 429 is waited out and retried once. Answers are saved into
 * the tables by the caller (FootballTables).
 */
@Component
public class FootballDataClient {

    private final RestClient http;
    private final String apiKey;
    private final int callsPerMinute;
    /** When each of the recent real API calls was made (for the calls-per-minute limit). */
    private final Deque<Instant> recentCalls = new ArrayDeque<>();

    public FootballDataClient(@Value("${football-data.key:}") String apiKey,
                              @Value("${football-data.base-url:https://api.football-data.org/v4}") String baseUrl,
                              @Value("${football-data.calls-per-minute:10}") int callsPerMinute) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
        this.callsPerMinute = Math.max(1, callsPerMinute);
    }

    /**
     * Waits until another call fits inside the plan's limit (default 10 per rolling minute),
     * then records it. Callers queue here instead of getting "rate limit" errors.
     */
    private void waitForSlot() {
        synchronized (recentCalls) {
            while (true) {
                Instant now = Instant.now();
                while (!recentCalls.isEmpty() && recentCalls.peekFirst().isBefore(now.minusSeconds(60))) {
                    recentCalls.pollFirst();
                }
                if (recentCalls.size() < callsPerMinute) {
                    recentCalls.addLast(now);
                    return;
                }
                long waitMs = Duration.between(now, recentCalls.peekFirst().plusSeconds(60)).toMillis() + 200;
                try {
                    recentCalls.wait(Math.max(waitMs, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One real API call, waiting for a free slot first and retrying once if the API still says 429. */
    private ResponseEntity<String> call(String path, Map<String, String> params) {
        for (int attempt = 0; ; attempt++) {
            waitForSlot();
            ResponseEntity<String> res = http.get()
                    .uri(b -> {
                        b.path(path);
                        params.forEach(b::queryParam);
                        return b.build();
                    })
                    .header("X-Auth-Token", apiKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .exchange((req, response) -> {
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        HttpHeaders h = new HttpHeaders();
                        String reset = response.getHeaders().getFirst("X-RequestCounter-Reset");
                        if (reset != null) {
                            h.set("X-RequestCounter-Reset", reset);
                        }
                        return ResponseEntity.status(response.getStatusCode()).headers(h).body(body);
                    });
            if (res.getStatusCode().value() != 429 || attempt >= 1) {
                return res;
            }
            // The API counts calls from elsewhere too (e.g. another app using this key): wait as told, then retry.
            long seconds = 60;
            try {
                seconds = Long.parseLong(String.valueOf(res.getHeaders().getFirst("X-RequestCounter-Reset")).trim());
            } catch (NumberFormatException ignored) {
                // keep 60
            }
            try {
                Thread.sleep(Math.min(Math.max(seconds, 1), 65) * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return res;
            }
        }
    }

    /**
     * One call to the API, waiting for a free slot first. The caller saves what it needs into the tables.
     * Errors come back with the API's status; without a key nothing is sent (503).
     */
    public ResponseEntity<String> fetch(String path, Map<String, String> params) {
        if (apiKey.isEmpty()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE,
                    "{\"message\":\"No football-data.org key. Add FOOTBALL_DATA_API_KEY to .env.\"}");
        }
        ResponseEntity<String> res = call(path, params);
        String body = res.getBody() == null || res.getBody().isBlank() ? "{}" : res.getBody();
        return json(HttpStatus.valueOf(res.getStatusCode().value()), body);
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return Json.response(status, body);
    }

    /** Small helper for building ordered query maps. */
    public static Map<String, String> params(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
