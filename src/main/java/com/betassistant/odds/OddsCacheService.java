package com.betassistant.odds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Saves whole-tournament odds pulls to JSON files (cache/football/&lt;tournament&gt;.json) so the app
 * can read them again without spending credits. Only a refresh calls The Odds API.
 */
@Service
public class OddsCacheService {

    private static final Pattern SAFE_LIST = Pattern.compile("[a-z0-9_,]*");

    private final OddsApiClient client;
    private final Path dir;
    private final List<String> tournaments;

    public OddsCacheService(OddsApiClient client,
                            @Value("${odds-cache.dir:cache}") String dir,
                            Environment env) {
        this.client = client;
        this.dir = Path.of(dir);
        // football.tournaments is a YAML list in application.yaml
        List<String> footballTournaments = Binder.get(env)
                .bind("football.tournaments", Bindable.listOf(String.class)).orElse(List.of());
        this.tournaments = footballTournaments.stream().map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> FOOTBALL_API_PREFIX + OddsApiClient.safeId(t)).toList();
    }

    /** The Odds API calls football "soccer"; we only use that word when talking to the API. */
    public static final String FOOTBALL_API_PREFIX = "soccer_";

    /** Our name for an API sport key, e.g. soccer_uefa_champs_league -> uefa_champs_league. */
    public static String footballName(String sportKey) {
        return sportKey.startsWith(FOOTBALL_API_PREFIX) ? sportKey.substring(FOOTBALL_API_PREFIX.length()) : sportKey;
    }

    /** The configured football tournaments as API sport keys (football.tournaments). */
    public List<String> tournaments() {
        return tournaments;
    }

    /**
     * Refreshes every configured tournament that currently has fixtures. The in-season check
     * (/sports) is free, so inactive tournaments cost nothing. Returns a JSON summary.
     */
    public String refreshAll(String markets, String bookmakers, int days) {
        ResponseEntity<String> sports = client.get("/sports", Map.of());
        if (!sports.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Could not load the sports list: " + sports.getBody());
        }
        String active = sports.getBody() == null ? "" : sports.getBody();
        StringBuilder out = new StringBuilder("[");
        for (String sport : tournaments) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append("{\"sport\":\"").append(sport).append("\",");
            if (!active.contains("\"key\":\"" + sport + "\"")) {
                out.append("\"status\":\"inactive\",\"credits\":0}");
                continue;
            }
            ResponseEntity<String> res = refresh(sport, markets, bookmakers, days);
            String cost = Optional.ofNullable(res.getHeaders().getFirst("x-requests-last")).orElse("0")
                    .replaceAll("[^0-9]", "");
            if (res.getStatusCode().is2xxSuccessful()) {
                int events = countMatches(res.getBody());
                out.append("\"status\":\"refreshed\",\"events\":").append(events)
                        .append(",\"credits\":").append(cost.isEmpty() ? "0" : cost).append('}');
            } else {
                out.append("\"status\":\"error ").append(res.getStatusCode().value())
                        .append("\",\"credits\":0}");
            }
        }
        return out.append(']').toString();
    }

    private static int countMatches(String body) {
        if (body == null) {
            return 0;
        }
        int count = 0;
        int i = 0;
        while ((i = body.indexOf("\"commence_time\"", i)) >= 0) {
            count++;
            i++;
        }
        return count;
    }

    /**
     * One API call for every event in the sport starting within {@code days}.
     * Cost = number of markets x regions (up to 10 bookmakers count as one region).
     */
    public ResponseEntity<String> refresh(String sport, String markets, String bookmakers, int days) {
        OddsApiClient.safeId(sport);
        markets = checkList(markets, "markets");
        bookmakers = checkList(bookmakers, "bookmakers");
        if (days < 1 || days > 60) {
            throw new IllegalArgumentException("days must be between 1 and 60");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", markets);
        params.put("bookmakers", bookmakers);
        params.put("oddsFormat", "decimal");
        params.put("dateFormat", "iso");
        params.put("commenceTimeFrom", now.toString());
        params.put("commenceTimeTo", now.plus(days, ChronoUnit.DAYS).toString());

        ResponseEntity<String> res = client.get("/sports/" + sport + "/odds", params);
        if (!res.getStatusCode().is2xxSuccessful()) {
            return res;
        }
        String cost = Optional.ofNullable(res.getHeaders().getFirst("x-requests-last")).orElse("");
        String json = "{\"sport\":\"" + sport + "\""
                + ",\"fetchedAt\":\"" + now + "\""
                + ",\"days\":" + days
                + ",\"markets\":\"" + markets + "\""
                + ",\"bookmakers\":\"" + bookmakers + "\""
                + ",\"creditsUsed\":\"" + cost.replaceAll("[^0-9]", "") + "\""
                + ",\"events\":" + res.getBody() + "}";
        try {
            Path target = file(sport);
            Files.createDirectories(target.getParent());
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write cache file for " + sport, e);
        }
        return ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders()).body(json);
    }

    /** Cached JSON for a sport, if a refresh has been done. Free. */
    public Optional<String> read(String sport) {
        Path f = file(OddsApiClient.safeId(sport));
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** API sport keys that have a cache file. */
    public List<String> cachedSports() {
        Path football = dir.resolve("football");
        if (!Files.isDirectory(football)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(football)) {
            List<String> out = new ArrayList<>();
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> FOOTBALL_API_PREFIX + n.substring(0, n.length() - 5))
                    .sorted()
                    .forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Football goes in cache/football/&lt;tournament&gt;.json; anything else in cache/other/. */
    private Path file(String sport) {
        if (sport.startsWith(FOOTBALL_API_PREFIX)) {
            return dir.resolve("football").resolve(footballName(sport) + ".json");
        }
        return dir.resolve("other").resolve(sport + ".json");
    }

    private static String checkList(String value, String name) {
        String v = value == null ? "" : value.replaceAll("\\s", "");
        if (v.isEmpty() || !SAFE_LIST.matcher(v).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return v;
    }
}
