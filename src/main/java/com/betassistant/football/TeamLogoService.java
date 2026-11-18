package com.betassistant.football;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Team crests from football-data.org (free key, 10 calls/minute).
 * One call per competition returns every team with a crest URL; the result is saved to
 * cache/football/teams/&lt;tournament&gt;.json and reused, so logos normally cost no calls.
 */
@Service
public class TeamLogoService {

    /** Our tournament names -> football-data.org competition codes (free tier). */
    private static final Map<String, String> COMPETITION_CODES = Map.of(
            "uefa_champs_league", "CL",
            "fifa_world_cup", "WC",
            "uefa_european_championship", "EC");

    private final RestClient http;
    private final String apiKey;
    private final Path dir;

    public TeamLogoService(@Value("${football-data.key:}") String apiKey,
                           @Value("${football-data.base-url:https://api.football-data.org/v4}") String baseUrl,
                           @Value("${odds-cache.dir:cache}") String cacheDir) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
        this.dir = Path.of(cacheDir, "football", "teams");
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /**
     * Teams JSON (football-data.org format: {"teams":[{"name","shortName","tla","crest"}...]})
     * for a tournament. Uses the saved file unless {@code refresh} is true.
     * Empty if the tournament isn't covered or no key is set and nothing is saved.
     */
    public Optional<String> teams(String tournament, boolean refresh) {
        String code = COMPETITION_CODES.get(tournament);
        if (code == null) {
            return Optional.empty();
        }
        Path file = dir.resolve(tournament + ".json");
        try {
            if (!refresh && Files.exists(file)) {
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            }
            if (!hasKey()) {
                return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
            }
            String body = http.get()
                    .uri("/competitions/{code}/teams", code)
                    .header("X-Auth-Token", apiKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            Files.createDirectories(dir);
            Files.writeString(file, body, StandardCharsets.UTF_8);
            return Optional.of(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
