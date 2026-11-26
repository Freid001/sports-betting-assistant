package com.sportsbettingassistant.football.live;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.sportsbettingassistant.json.Json;

/**
 * GOAL API (https://api.goal-api.com/v1), free plan 1,000 calls a day. Read-only. The key comes from
 * the key saved on the Settings page, is sent as a Bearer token and is never shown to the browser.
 * Also keeps the calls-left numbers from the last response, for the Live Stats pop-up.
 * Test route: GET /api/football/goal-api?path=/fixtures/live
 */
@RestController
public class GoalApiClient {

    /** Only simple read paths like /fixtures/live or /fixtures/123/statistics (no "." or ".." segments). */
    private static final Pattern SAFE_PATH =
            Pattern.compile("^/[A-Za-z0-9_\\-]+(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_\\-:.]+){0,4}$");
    private static final String[] RATE_HEADERS = {"X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "X-RateLimit-Type"};

    private final RestClient http;
    private final String apiKey;          // tests only: the app uses the Settings page's key
    private com.sportsbettingassistant.settings.SettingsService settings;   // the Settings page's key, if saved
    private volatile Map<String, Object> usage = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GoalApiClient(@Value("${goal-api.base-url:https://api.goal-api.com/v1}") String baseUrl) {
        this("", baseUrl);
    }

    public GoalApiClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettings(com.sportsbettingassistant.settings.SettingsService settings) {
        this.settings = settings;
    }

    /** The key in use now, from the Settings page (read on every call, so a new key works without a restart). */
    private String key() {
        return settings != null ? settings.apiKey("goal-api") : apiKey;
    }

    public boolean configured() {
        return !key().isEmpty();
    }

    /** Calls left today etc. from the last response: limit, remaining, reset, checkedAt. */
    public Map<String, Object> usage() {
        return usage;
    }

    /** Lets the service restore saved usage after a restart. */
    void restoreUsage(Map<String, Object> saved) {
        if (saved != null && usage.isEmpty()) {
            usage = new LinkedHashMap<>(saved);
        }
    }

    /** Remaining calls today, or -1 if not known yet. */
    public long remaining() {
        Object r = usage.get("remaining");
        try {
            return r == null ? -1 : Long.parseLong(String.valueOf(r));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** One GET, parsed. Throws with GOAL API's message if it fails. */
    public Map<String, Object> getJson(String path, Map<String, String> params) {
        Raw raw = fetch(path, params);
        if (raw.status / 100 != 2) {
            String msg = raw.body.length() > 200 ? raw.body.substring(0, 200) : raw.body;
            throw new IllegalStateException("GOAL API " + raw.status + ": " + msg);
        }
        return new BasicJsonParser().parseMap(com.sportsbettingassistant.json.Json.unescapeUnicode(raw.body));
    }

    private record Raw(int status, String body, HttpHeaders rate) {
    }

    private Raw fetch(String path, Map<String, String> params) {
        if (!SAFE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("bad path");
        }
        if (!configured()) {
            throw new IllegalStateException("No GOAL API key: add it on the Settings page.");
        }
        return http.get()
                .uri(b -> {
                    b.path(path);
                    params.forEach(b::queryParam);
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key())
                .exchange((req, res) -> {
                    HttpHeaders h = new HttpHeaders();
                    for (String n : RATE_HEADERS) {
                        String v = res.getHeaders().getFirst(n);
                        if (v != null) {
                            h.set(n, v);
                        }
                    }
                    if (h.getFirst("X-RateLimit-Remaining") != null) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("limit", h.getFirst("X-RateLimit-Limit"));
                        u.put("remaining", h.getFirst("X-RateLimit-Remaining"));
                        u.put("reset", h.getFirst("X-RateLimit-Reset"));
                        u.put("checkedAt", Instant.now().toString());
                        usage = u;
                    }
                    return new Raw(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8), h);
                });
    }

    /** Test route: one GET to GOAL API, returning its JSON and the calls-left headers. */
    @GetMapping("/api/football/goal-api")
    public ResponseEntity<String> test(@RequestParam String path, @RequestParam Map<String, String> all) {
        all.remove("path");
        try {
            Raw raw = fetch(path, all);
            HttpHeaders h = new HttpHeaders();
            for (String n : RATE_HEADERS) {
                String v = raw.rate.getFirst(n);
                if (v != null) {
                    h.set(n, v);
                }
            }
            h.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.status(raw.status).headers(h).body(raw.body);
        } catch (RuntimeException e) {
            return Json.message(e instanceof IllegalArgumentException ? 400 : 503, e.getMessage());
        }
    }
}
