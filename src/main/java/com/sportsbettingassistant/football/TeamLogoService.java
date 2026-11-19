package com.sportsbettingassistant.football;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Team crests from football-data.org (free key, 10 calls/minute).
 * One call per competition returns every team with a crest URL and country; they are saved in the teams
 * tables (FootballTables) and reused, so logos normally cost no calls.
 */
@Service
public class TeamLogoService {

    private final RestClient http;
    private final String apiKey;
    private final FootballTables tables;

    public TeamLogoService(@Value("${football-data.key:}") String apiKey,
                           @Value("${football-data.base-url:https://api.football-data.org/v4}") String baseUrl,
                           FootballTables tables) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = RestClient.create(baseUrl);
        this.tables = tables;
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /**
     * Teams JSON (football-data.org format: {"teams":[{"id","name","shortName","tla","crest","area"}...]})
     * for a tournament. Uses the saved list unless {@code refresh} is true.
     * Empty if the tournament isn't covered or no key is set and nothing is saved.
     */
    public Optional<String> teams(String tournament, boolean refresh) {
        String code = Competitions.CODES.get(tournament);
        if (code == null) {
            return Optional.empty();
        }
        Optional<String> saved = tables.teamsJson(code);
        if ((!refresh && saved.isPresent()) || !hasKey()) {
            return saved;
        }
        String body = http.get()
                .uri("/competitions/{code}/teams", code)
                .header("X-Auth-Token", apiKey)
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .body(String.class);
        if (body == null) {
            return saved;
        }
        tables.saveTeams(code, body);
        return tables.teamsJson(code);
    }
}
