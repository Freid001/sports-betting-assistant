package com.sportsbettingassistant.odds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.sportsbettingassistant.json.Json;

/**
 * Read-only client for The Odds API (https://the-odds-api.com/liveapi/guides/v4/).
 * The API key is the one saved on the Settings page; it is never sent to the browser.
 */
@Component
public class OddsApiClient {

    /** Quota headers returned by The Odds API, passed through to the browser. */
    public static final List<String> QUOTA_HEADERS =
            List.of("x-requests-remaining", "x-requests-used", "x-requests-last");

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_]+");

    private final RestClient http;
    private final String apiKey;          // tests only: the app uses the Settings page's key
    private com.sportsbettingassistant.settings.SettingsService settings;   // the Settings page's key, if saved

    @org.springframework.beans.factory.annotation.Autowired
    public OddsApiClient(@Value("${odds-api.base-url:https://api.the-odds-api.com/v4}") String baseUrl) {
        this("", baseUrl);
    }

    public OddsApiClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettings(com.sportsbettingassistant.settings.SettingsService settings) {
        this.settings = settings;
    }

    /** The key in use now, from the Settings page (read on every call, so a new key works without a restart). */
    private String key() {
        return settings != null ? settings.apiKey("odds-api") : apiKey;
    }

    public boolean hasKey() {
        return !key().isEmpty();
    }

    /** Checks a sport key or event id before it goes into a URL path. */
    public static String safeId(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid id: " + value);
        }
        return value;
    }

    /** GET a path on The Odds API and return its JSON body, status and quota headers. */
    public ResponseEntity<String> get(String path, Map<String, String> params) {
        if (!hasKey()) {
            return Json.message(503, "No Odds API key: add it on the Settings page.");
        }
        String apiKey = key();
        return http.get()
                .uri(builder -> {
                    builder.path(path);
                    params.forEach((name, value) -> {
                        if (value != null && !value.isBlank()) {
                            builder.queryParam(name, value.trim());
                        }
                    });
                    builder.queryParam("apiKey", apiKey);
                    return builder.build();
                })
                .exchange((request, response) -> {
                    HttpHeaders out = new HttpHeaders();
                    out.setContentType(MediaType.APPLICATION_JSON);
                    for (String name : QUOTA_HEADERS) {
                        String value = response.getHeaders().getFirst(name);
                        if (value != null) {
                            out.set(name, value);
                        }
                    }
                    String body = readBody(response.getBody());
                    return ResponseEntity.status(response.getStatusCode()).headers(out).body(body);
                });
    }

    private static String readBody(java.io.InputStream in) throws IOException {
        return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
