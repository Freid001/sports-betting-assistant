package com.sportsbettingassistant.football;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.store.Store;

/**
 * "Refresh Stats" on the Football page: fetches season results, fixtures and top scorers from
 * football-data.org and saves them in the database tables (FootballTables). Runs in the background at the
 * plan's pace; match pages then read the tables and make no calls (except head to head the first time).
 */
@Service
public class StatsRefreshService {

    static final Map<String, String> FINISHED = FootballDataClient.params("status", "FINISHED");
    static final Map<String, String> SCHEDULED = FootballDataClient.params("status", "SCHEDULED");
    static final Map<String, String> SCORERS = FootballDataClient.params("limit", "100");
    static final int SQUAD_DAYS = 14;                                           // squads for teams playing within 2 weeks
    static final java.time.Duration SQUAD_KEEP = java.time.Duration.ofDays(30); // ...fetched again after a month (transfers)
    static final Map<String, String> H2H = FootballDataClient.params("limit", "6");

    /** Country of a team (football-data.org area name) -> its top league on the free plan. */
    static final Map<String, String> COUNTRY_LEAGUES = Map.of(
            "England", "PL", "Germany", "BL1", "Spain", "PD", "Italy", "SA",
            "France", "FL1", "Netherlands", "DED", "Portugal", "PPL", "Brazil", "BSA");

    private final FootballDataClient client;
    private final OddsCacheService odds;
    private final TeamLogoService logos;
    private final FootballTables tables;
    private final Store store;
    private final List<String> leagues;
    private final boolean leagueScorers;

    private volatile Status status = new Status();

    public StatsRefreshService(FootballDataClient client, OddsCacheService odds, TeamLogoService logos,
                               FootballTables tables, Store store,
                               @Value("${football-data.league-scorers:true}") boolean leagueScorers,
                               org.springframework.core.env.Environment env) {
        this.client = client;
        this.odds = odds;
        this.logos = logos;
        this.tables = tables;
        this.store = store;
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

    /** When Refresh Stats last finished, if it has. */
    public Optional<String> lastRefresh() {
        return store.refreshedAt("stats").map(Instant::toString);
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

    /**
     * Per tournament with fixtures: season results, top scorers, upcoming fixtures (3 calls).
     * Per domestic league its teams play in: season results (+ top scorers) (1-2 calls).
     * League tables are worked out from results (no calls).
     */
    private void run(Status s) {
        try {
            List<String> active = new ArrayList<>();
            for (String sport : odds.tournaments()) {
                String name = OddsCacheService.footballName(sport);
                if (Competitions.CODES.containsKey(name) && odds.hasFixtures(sport)) {
                    active.add(name);
                }
            }
            List<String> needed = new ArrayList<>();
            for (String t : active) {
                logos.teams(t, false);   // makes sure the crest list (with each team's country) is saved
                for (String country : tables.countries(Competitions.CODES.get(t))) {
                    String code = COUNTRY_LEAGUES.get(country);
                    if (code != null && leagues.contains(code) && !needed.contains(code)
                            && active.stream().noneMatch(a -> code.equals(Competitions.CODES.get(a)))) {
                        needed.add(code);
                    }
                }
            }
            s.total = active.size() * 3 + needed.size() * (leagueScorers ? 2 : 1);

            for (String t : active) {
                String code = Competitions.CODES.get(t);
                s.step = "Results in " + code;
                fetch("/competitions/" + code + "/matches", FINISHED, s, json -> tables.saveMatches(code, "FINISHED", json));
                s.step = "Top scorers in " + code;
                fetch("/competitions/" + code + "/scorers", SCORERS, s, json -> tables.saveScorers(code, json));
                s.step = "Fixtures in " + code;
                fetch("/competitions/" + code + "/matches", SCHEDULED, s, json -> tables.saveMatches(code, "SCHEDULED", json));
            }
            for (String code : needed) {
                s.step = "Results in " + code;
                fetch("/competitions/" + code + "/matches", FINISHED, s, json -> tables.saveMatches(code, "FINISHED", json));
                if (leagueScorers) {
                    s.step = "Top scorers in " + code;
                    fetch("/competitions/" + code + "/scorers", SCORERS, s, json -> tables.saveScorers(code, json));
                }
            }

            // Squads (players and positions) of the teams playing in the next 2 weeks; kept for a month, since
            // squads only change much in the transfer windows. 1 call per team, so the first refresh takes longer.
            Instant soon = Instant.now();
            List<Long> squads = tables.teamsPlayingWithin(SQUAD_DAYS).stream()
                    .filter(id -> tables.squadSavedAt(id).map(t -> t.isBefore(soon.minus(SQUAD_KEEP))).orElse(true))
                    .toList();
            s.total += squads.size();
            int n = 0;
            for (long id : squads) {
                s.step = "Squads (" + (++n) + " of " + squads.size() + ")";
                fetch("/teams/" + id, Map.of(), s, json -> tables.saveSquad(id, json));
            }

            Instant now = Instant.now();
            store.markRefreshed("stats", now);
            s.finishedAt = now.toString();
            s.step = "Done";
        } catch (Exception e) {
            s.errors.add(String.valueOf(e.getMessage()));
            s.step = "Stopped: " + e.getMessage();
        } finally {
            s.running = false;
        }
    }

    /** One call (the client keeps the pace); a good answer is saved, errors are noted, not fatal. */
    private void fetch(String path, Map<String, String> params, Status s, Consumer<String> save) {
        ResponseEntity<String> res = client.fetch(path, params);
        s.done++;
        if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
            save.accept(res.getBody());
        } else {
            s.errors.add(path + ": HTTP " + res.getStatusCode().value());
        }
    }
}
