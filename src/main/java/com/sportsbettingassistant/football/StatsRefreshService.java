package com.sportsbettingassistant.football;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.store.Store;

/**
 * "Refresh Stats" on the Football page: fetches teams, season results, fixtures, league tables, top scorers and
 * squads from GOAL API (GoalStats) and saves them in the database tables (FootballTables). Runs in the
 * background; match pages then read the tables and make no calls (except head to head the first time).
 */
@Service
public class StatsRefreshService {

    static final int SQUAD_DAYS = 14;                          // squads for teams playing within 2 weeks
    static final Duration SQUAD_KEEP = Duration.ofDays(30);    // ...fetched again after a month (transfers)
    static final List<String> DEFAULT_LEAGUES = List.of("PL", "ELC", "BL1", "SA", "PD", "FL1", "DED", "PPL");

    private final GoalStats goal;
    private final OddsCacheService odds;
    private final FootballTables tables;
    private final Store store;
    private final List<String> leagues;
    private final boolean leagueScorers;

    private volatile Status status = new Status();

    public StatsRefreshService(GoalStats goal, OddsCacheService odds, FootballTables tables, Store store,
                               @Value("${stats.league-scorers:true}") boolean leagueScorers,
                               org.springframework.core.env.Environment env) {
        this.goal = goal;
        this.odds = odds;
        this.tables = tables;
        this.store = store;
        this.leagueScorers = leagueScorers;
        this.leagues = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("stats.leagues", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(DEFAULT_LEAGUES);
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

    /** Runs a refresh now, on this thread (tests). */
    Status runNow() {
        Status s = new Status();
        s.running = true;
        status = s;
        run(s);
        return s;
    }

    /**
     * Per competition with fixtures: teams, results (1-3 calls), fixtures, league table and top scorers.
     * Per domestic league its clubs play in (e.g. Bundesliga for Bayern in the Champions League): results,
     * table (+ top scorers). Then squads of teams playing in the next 2 weeks (1 call each, kept for a month).
     */
    private void run(Status s) {
        try {
            List<String> active = new ArrayList<>();
            for (String sport : odds.tournaments()) {
                String code = Competitions.CODES.get(OddsCacheService.footballName(sport));
                if (code != null && odds.hasFixtures(sport) && !active.contains(code)) {
                    active.add(code);
                }
            }
            s.total = active.size() * 5;
            for (String code : active) {
                call(s, "Teams in " + code, () -> { goal.saveTeams(code); return 1; });
            }
            List<String> needed = new ArrayList<>();
            for (String code : active) {
                for (String country : tables.countries(code)) {
                    String league = Competitions.COUNTRY_LEAGUES.get(country);
                    if (league != null && leagues.contains(league) && !active.contains(league) && !needed.contains(league)) {
                        needed.add(league);
                    }
                }
            }
            s.total += needed.size() * (leagueScorers ? 3 : 2);

            for (String code : active) {
                call(s, "Results in " + code, () -> goal.saveResults(code));
                call(s, "Fixtures in " + code, () -> { goal.saveFixtures(code); return 1; });
                call(s, "Table for " + code, () -> { goal.saveStandings(code); return 1; });
                call(s, "Top scorers in " + code, () -> { goal.saveScorers(code); return 1; });
            }
            for (String code : needed) {
                call(s, "Results in " + code, () -> goal.saveResults(code));
                call(s, "Table for " + code, () -> { goal.saveStandings(code); return 1; });
                if (leagueScorers) {
                    call(s, "Top scorers in " + code, () -> { goal.saveScorers(code); return 1; });
                }
            }

            // Squads (players and positions) of the teams playing in the next 2 weeks; kept for a month, since
            // squads only change much in the transfer windows. 1 call per team, so the first refresh takes longer.
            Instant soon = Instant.now();
            List<Long> squads = tables.teamsPlayingWithin(SQUAD_DAYS).stream()
                    .filter(id -> tables.squadSavedAt(id).map(t -> t.isBefore(soon.minus(SQUAD_KEEP))).orElse(true))
                    .filter(id -> tables.goalId(id).isPresent())
                    .toList();
            s.total += squads.size();
            int n = 0;
            for (long id : squads) {
                String step = "Squads (" + (++n) + " of " + squads.size() + ")";
                call(s, step, () -> goal.saveSquad(id) ? 1 : 0);
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

    /** One step (it returns the calls it made); errors are noted, not fatal. */
    private void call(Status s, String step, IntSupplier work) {
        s.step = step;
        try {
            int calls = work.getAsInt();
            s.done += Math.max(1, calls);
            if (calls > 1) {
                s.total += calls - 1;   // a season's results took more than one page
            }
        } catch (RuntimeException e) {
            s.done++;
            s.errors.add(step + ": " + e.getMessage());
        }
    }
}
