package com.betassistant.football;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.betassistant.odds.OddsCacheService;

/**
 * "Refresh stats": fetches every football-data.org answer the match pages need for my tournaments
 * and saves it (cache/football/data/...). Runs in the background at the plan's pace; match pages
 * then read the saved copies and make no calls.
 *
 * Uses whole-competition calls (a season of results in one request) rather than one call per team,
 * only for tournaments that have fixtures and the leagues their teams play in (~17 calls).
 */
@Service
public class StatsRefreshService {

    /** Our tournament names -> football-data.org competition codes (free plan). */
    static final Map<String, String> COMPETITION_CODES = Map.of(
            "uefa_champs_league", "CL",
            "fifa_world_cup", "WC",
            "uefa_european_championship", "EC");

    /** Same query strings as the match page uses, so they share cache files. */
    static final Map<String, String> FINISHED = FootballDataClient.params("status", "FINISHED");
    static final Map<String, String> SCHEDULED = FootballDataClient.params("status", "SCHEDULED");
    static final Map<String, String> SCORERS = FootballDataClient.params("limit", "100");
    static final Map<String, String> H2H = FootballDataClient.params("limit", "6");

    private final FootballDataClient client;
    private final OddsCacheService odds;
    private final TeamLogoService logos;
    private final Path stampFile;
    private final int days;
    private final List<String> leagues;
    private final boolean leagueScorers;

    private volatile Status status = new Status();

    public StatsRefreshService(FootballDataClient client, OddsCacheService odds, TeamLogoService logos,
                               @Value("${odds-cache.dir:cache}") String cacheDir,
                               @Value("${football-data.refresh-days-ahead:30}") int days,
                               @Value("${football-data.league-scorers:true}") boolean leagueScorers,
                               org.springframework.core.env.Environment env) {
        this.client = client;
        this.odds = odds;
        this.logos = logos;
        this.stampFile = Path.of(cacheDir, "football", "data", "_last_refresh.txt");
        this.days = days;
        this.leagueScorers = leagueScorers;
        this.leagues = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("football-data.leagues", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of("PL", "ELC", "BL1", "SA", "PD", "FL1", "DED", "PPL"));
    }

    /** Progress of the current or last refresh. */
    public static class Status {
        public boolean running;
        public int done;
        public int total;
        public String step = "";
        public String startedAt;
        public String finishedAt;
        public List<String> errors = new ArrayList<>();
    }

    public Status status() {
        Status s = status;
        if (s.finishedAt == null && !s.running) {
            s.finishedAt = lastRefresh().orElse(null);
        }
        return s;
    }

    public Optional<String> lastRefresh() {
        try {
            return Files.exists(stampFile) ? Optional.of(Files.readString(stampFile).trim()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Starts a refresh in the background. Returns false if one is already running. */
    public synchronized boolean start() {
        if (status.running) {
            return false;
        }
        Status s = new Status();
        s.running = true;
        s.startedAt = Instant.now().toString();
        s.step = "Starting";
        status = s;
        Thread t = new Thread(() -> run(s), "stats-refresh");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Country of a team (football-data.org area name) -> its top league on the free plan. */
    static final Map<String, String> COUNTRY_LEAGUES = Map.of(
            "England", "PL", "Germany", "BL1", "Spain", "PD", "Italy", "SA",
            "France", "FL1", "Netherlands", "DED", "Portugal", "PPL", "Brazil", "BSA");

    /**
     * Per tournament with fixtures: season results, top scorers, upcoming fixtures (3 calls).
     * Per domestic league its teams play in: season results (+ top scorers) (1-2 calls).
     * League tables are worked out from results (no calls); head to head is fetched when a match
     * page is first opened (1 call, saved for a week).
     */
    private void run(Status s) {
        try {
            List<String> active = new ArrayList<>();
            for (String sport : odds.tournaments()) {
                String name = OddsCacheService.footballName(sport);
                if (COMPETITION_CODES.containsKey(name)
                        && odds.read(sport).map(j -> j.contains("\"commence_time\"")).orElse(false)) {
                    active.add(name);
                }
            }
            List<String> needed = new ArrayList<>();
            for (String t : active) {
                logos.teams(t, false).ifPresent(json -> listOf(parse(json), "teams").forEach(team -> {
                    String code = COUNTRY_LEAGUES.get(String.valueOf(asMap(team.get("area")).get("name")));
                    if (code != null && leagues.contains(code) && !needed.contains(code)) {
                        needed.add(code);
                    }
                }));
            }
            s.total = active.size() * 3 + needed.size() * (leagueScorers ? 2 : 1);

            for (String t : active) {
                String code = COMPETITION_CODES.get(t);
                s.step = "Results in " + code;
                fetch("/competitions/" + code + "/matches", FINISHED, s);
                s.step = "Top scorers in " + code;
                fetch("/competitions/" + code + "/scorers", SCORERS, s);
                s.step = "Fixtures in " + code;
                fetch("/competitions/" + code + "/matches", SCHEDULED, s);
            }
            for (String code : needed) {
                s.step = "Results in " + code;
                fetch("/competitions/" + code + "/matches", FINISHED, s);
                if (leagueScorers) {
                    s.step = "Top scorers in " + code;
                    fetch("/competitions/" + code + "/scorers", SCORERS, s);
                }
            }

            String now = Instant.now().toString();
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, now, StandardCharsets.UTF_8);
            s.finishedAt = now;
            s.step = "Done";
        } catch (Exception e) {
            s.errors.add(String.valueOf(e.getMessage()));
            s.step = "Stopped: " + e.getMessage();
        } finally {
            s.running = false;
        }
    }

    /**
     * A league table worked out from a competition's saved season results, in football-data.org's
     * standings format. Empty if the results haven't been saved.
     */
    public Optional<String> standings(String code) {
        Optional<String> saved = client.cached("/competitions/" + code + "/matches", FINISHED);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> matches = listOf(parse(saved.get()), "matches");
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        String compName = code;
        for (Map<String, Object> m : matches) {
            Map<String, Object> score = asMap(asMap(m.get("score")).get("fullTime"));
            if (!(score.get("home") instanceof Number) || !(score.get("away") instanceof Number)) {
                continue;
            }
            Object stage = m.get("stage");
            if (stage != null && !List.of("REGULAR_SEASON", "LEAGUE_STAGE", "GROUP_STAGE").contains(String.valueOf(stage))) {
                continue;   // knockout games don't count towards the table
            }
            Map<String, Object> comp = asMap(m.get("competition"));
            if (comp.get("name") != null) {
                compName = String.valueOf(comp.get("name"));
            }
            int hg = ((Number) score.get("home")).intValue();
            int ag = ((Number) score.get("away")).intValue();
            add(rows, asMap(m.get("homeTeam")), hg, ag);
            add(rows, asMap(m.get("awayTeam")), ag, hg);
        }
        List<Map<String, Object>> table = new ArrayList<>(rows.values());
        table.sort((x, y) -> {
            int c = Integer.compare((int) y.get("points"), (int) x.get("points"));
            if (c == 0) {
                c = Integer.compare((int) y.get("goalDifference"), (int) x.get("goalDifference"));
            }
            if (c == 0) {
                c = Integer.compare((int) y.get("goalsFor"), (int) x.get("goalsFor"));
            }
            return c;
        });
        for (int i = 0; i < table.size(); i++) {
            table.get(i).put("position", i + 1);
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("type", "TOTAL");
        total.put("table", table);
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("code", code);
        comp.put("name", compName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("competition", comp);
        out.put("calculated", true);
        out.put("standings", List.of(total));
        return Optional.of(toJson(out));
    }

    private static void add(Map<Long, Map<String, Object>> rows, Map<String, Object> team, int gf, int ga) {
        long id = num(team.get("id"));
        Map<String, Object> r = rows.computeIfAbsent(id, k -> {
            Map<String, Object> n = new LinkedHashMap<>();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("name", team.get("name"));
            t.put("shortName", team.get("shortName"));
            n.put("team", t);
            for (String f : List.of("playedGames", "won", "draw", "lost", "points", "goalsFor", "goalsAgainst", "goalDifference")) {
                n.put(f, 0);
            }
            return n;
        });
        inc(r, "playedGames", 1);
        inc(r, gf > ga ? "won" : gf == ga ? "draw" : "lost", 1);
        inc(r, "points", gf > ga ? 3 : gf == ga ? 1 : 0);
        inc(r, "goalsFor", gf);
        inc(r, "goalsAgainst", ga);
        inc(r, "goalDifference", gf - ga);
    }

    private static void inc(Map<String, Object> r, String key, int by) {
        r.put(key, (int) r.get(key) + by);
    }

    /**
     * A team's finished matches from every saved competition (no API calls), newest first,
     * as {"matches":[...]}. Empty if nothing has been saved for it yet.
     */
    public Optional<String> teamResults(long teamId) {
        List<String> matches = resultsIndex().get(teamId);
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("{\"matches\":[" + String.join(",", matches.subList(0, Math.min(30, matches.size()))) + "]}");
    }

    /** Team id -> its finished matches as JSON, newest first. Built once from the saved season files. */
    private volatile Map<Long, List<String>> index = Map.of();
    private volatile String indexKey = "";

    /** Rebuilds the index only when a saved results file has changed (i.e. after Refresh stats). */
    private Map<Long, List<String>> resultsIndex() {
        List<String> codes = new ArrayList<>(COMPETITION_CODES.values());
        codes.addAll(leagues);
        StringBuilder key = new StringBuilder();
        for (String code : codes) {
            key.append(code).append('=')
               .append(client.cachedTime("/competitions/" + code + "/matches", FINISHED).map(Instant::toString).orElse("-"))
               .append(';');
        }
        if (key.toString().equals(indexKey)) {
            return index;
        }
        synchronized (this) {
            if (key.toString().equals(indexKey)) {
                return index;
            }
            Map<Long, List<Map<String, Object>>> byTeam = new LinkedHashMap<>();
            for (String code : codes) {
                client.cached("/competitions/" + code + "/matches", FINISHED).ifPresent(json ->
                        listOf(parse(json), "matches").forEach(m -> {
                            byTeam.computeIfAbsent(num(asMap(m.get("homeTeam")).get("id")), k -> new ArrayList<>()).add(m);
                            byTeam.computeIfAbsent(num(asMap(m.get("awayTeam")).get("id")), k -> new ArrayList<>()).add(m);
                        }));
            }
            Map<Long, List<String>> built = new java.util.HashMap<>();
            byTeam.forEach((team, list) -> {
                list.sort((x, y) -> String.valueOf(y.get("utcDate")).compareTo(String.valueOf(x.get("utcDate"))));
                List<String> json = new ArrayList<>();
                for (int i = 0; i < Math.min(30, list.size()); i++) {
                    json.add(toJson(list.get(i)));
                }
                built.put(team, json);
            });
            index = built;
            indexKey = key.toString();
            return index;
        }
    }

    /** Always a fresh call (the limiter in FootballDataClient keeps the pace). Errors are noted, not fatal. */
    private ResponseEntity<String> fetch(String path, Map<String, String> params, Status s) {
        ResponseEntity<String> res = client.get(path, params, Duration.ZERO);
        s.done++;
        if (!res.getStatusCode().is2xxSuccessful()) {
            s.errors.add(path + ": HTTP " + res.getStatusCode().value());
        }
        return res;
    }

    // ---- tiny JSON helpers (Spring Boot's dependency-free parser) ----

    private static Map<String, Object> parse(String json) {
        try {
            return new BasicJsonParser().parseMap(json);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> parseBody(ResponseEntity<String> res) {
        return res.getStatusCode().is2xxSuccessful() && res.getBody() != null ? parse(res.getBody()) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Minimal JSON writer for the parsed maps/lists (strings, numbers, booleans, null). */
    private static String toJson(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(toJson(l.get(i)));
            }
            return b.append(']').toString();
        }
        if (o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return quote(String.valueOf(o));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
