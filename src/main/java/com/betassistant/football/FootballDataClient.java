package com.betassistant.football;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Read-only football-data.org client with a file cache (cache/football/data/...).
 * A cached answer is reused until it is older than its time-to-live, so a match page
 * makes a handful of calls the first time and none after that. Real calls are queued so
 * they never go over the plan's limit (free plan: 10 calls/minute).
 */
@Component
public class FootballDataClient {

    private final RestClient http;
    private final String apiKey;
    private final Path dir;
    private final int callsPerMinute;
    /** When each of the recent real API calls was made (for the calls-per-minute limit). */
    private final Deque<Instant> recentCalls = new ArrayDeque<>();

    public FootballDataClient(@Value("${football-data.key:}") String apiKey,
                              @Value("${football-data.base-url:https://api.football-data.org/v4}") String baseUrl,
                              @Value("${odds-cache.dir:cache}") String cacheDir,
                              @Value("${football-data.calls-per-minute:10}") int callsPerMinute) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
        this.dir = Path.of(cacheDir, "football", "data");
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
     * GET {@code path} (e.g. /teams/57/matches) with query params, reusing the cached copy if it is
     * younger than {@code ttl}. Errors (e.g. 403 for paid-only data, 429 rate limit) are returned as
     * JSON with the API's status and are not cached.
     */
    public ResponseEntity<String> get(String path, Map<String, String> params, Duration ttl) {
        Path file = dir.resolve(cacheName(path, params));
        try {
            if (Files.exists(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(ttl))) {
                return json(HttpStatus.OK, Files.readString(file, StandardCharsets.UTF_8));
            }
            if (apiKey.isEmpty()) {
                return json(HttpStatus.SERVICE_UNAVAILABLE,
                        "{\"message\":\"No football-data.org key. Add FOOTBALL_DATA_API_KEY to .env.\"}");
            }
            ResponseEntity<String> res = call(path, params);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, res.getBody(), StandardCharsets.UTF_8);
                return json(HttpStatus.OK, res.getBody());
            }
            // Use an old copy rather than nothing if the API refuses (e.g. rate limit).
            if (Files.exists(file)) {
                return json(HttpStatus.OK, Files.readString(file, StandardCharsets.UTF_8));
            }
            String body = res.getBody() == null || res.getBody().isBlank() ? "{}" : res.getBody();
            return json(HttpStatus.valueOf(res.getStatusCode().value()), body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The saved copy of a call, if any (never calls the API). */
    public Optional<String> cached(String path, Map<String, String> params) {
        Path file = dir.resolve(cacheName(path, params));
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** When the saved copy of a call was written, if there is one. */
    public Optional<Instant> cachedTime(String path, Map<String, String> params) {
        Path file = dir.resolve(cacheName(path, params));
        try {
            return Files.exists(file) ? Optional.of(Files.getLastModifiedTime(file).toInstant()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** /teams/57/matches + {status=FINISHED, limit=30} -> teams_57_matches__limit-30_status-FINISHED.json */
    private static String cacheName(String path, Map<String, String> params) {
        StringBuilder name = new StringBuilder(path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9]+", "_"));
        if (!params.isEmpty()) {
            name.append('_');
            new java.util.TreeMap<>(params).forEach((k, v) ->
                    name.append('_').append(k).append('-').append(v.replaceAll("[^A-Za-z0-9]+", "")));
        }
        return name + ".json";
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
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
