package com.sportsbettingassistant.football.live;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.football.Competitions;
import com.sportsbettingassistant.football.Games;
import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * Live Stats from GOAL API for the games you tick (up to max-games).
 * <p>
 * While a ticked game is being played, every refresh-minutes it fetches: 1 call per competition playing
 * (today's scores and minutes for that competition) + 1 call per live ticked game (its match stats).
 * Nothing is fetched when no ticked game is on. Everything is saved in the live tables (live_games...),
 * so pages read the saved copy and a restart keeps your ticks.
 * <p>
 * xG: GOAL API has no xG, so we estimate it from shot counts: each shot inside the box (not a penalty)
 * 0.12, each shot outside the box 0.035, each penalty 0.76 (typical average values).
 * <p>
 * Snapshots: a game's stats at points in the match ('30', 'HT', '60', '80', 'FT') for the match page's timeline.
 * {@link #replay} loads a past game once (2 calls) as HT and FT snapshots; games with snapshots are kept 14 days.
 */
@Service
public class LiveService {

    private static final Logger log = LoggerFactory.getLogger(LiveService.class);
    private static final ZoneId UK = ZoneId.of("Europe/London");
    /** How long the list of upcoming games is reused before the pop-up fetches it again. */
    private static final Duration LIST_TTL = Duration.ofHours(3);
    /** A ticked game is watched from just before kick-off until it finishes (or 3 hours after kick-off at most). */
    private static final Duration BEFORE = Duration.ofMinutes(2), AFTER = Duration.ofHours(3);
    /** Timeline order of snapshots. */
    static final List<String> PERIODS = List.of("30", "HT", "60", "80", "FT");
    /** How long games with snapshots are kept (other games: 1 day after kick-off). */
    private static final Duration KEEP_SNAPSHOTS = Duration.ofDays(14);

    private final GoalApiClient api;
    private final Store store;
    private final Games games;
    private final Map<String, String> leagues;   // our tournament key -> GOAL API league id
    private final int refreshMinutes, maxGames, reserve;

    private final Map<String, Map<String, Object>> fixtures = new LinkedHashMap<>();  // id -> game summary
    private final Set<String> tracked = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> stats = new LinkedHashMap<>();     // id -> latest stats
    private final Map<String, Map<String, Map<String, Object>>> snapshots = new LinkedHashMap<>();  // id -> period -> snapshot
    /** What happened in each followed game, worked out refresh by refresh (goals, cards, shots on target...). */
    private final Map<String, List<Map<String, Object>>> timeline = new LinkedHashMap<>();
    /** Games whose official goals and cards were already asked for this run (finished games: once). */
    private final Set<String> officialTried = new LinkedHashSet<>();
    private final Map<String, Instant> lastOfficial = new java.util.HashMap<>();   // when a game's official feeds were last read
    private final Map<String, String[]> keepers = new java.util.HashMap<>();       // each game's goalkeepers [home, away]
    static final Duration OFFICIAL_EVERY = Duration.ofMinutes(5);
    /** Every player with a shot in the last refresh (side -> players), to see who scored or hit the target since. */
    private final Map<String, Map<String, List<Map<String, Object>>>> lastPlayers = new LinkedHashMap<>();
    /** Games (games.id) picked to follow before GOAL API lists them. Ticked on match day. */
    private final Set<Long> following = new LinkedHashSet<>();
    /** Competition and day already looked up for followed games (so a game GOAL API doesn't list costs 1 call a day). */
    private final Set<String> lookedUp = new LinkedHashSet<>();
    private Instant lastRefresh, lastList;
    private ApplicationEventPublisher events;   // tells others (the live odds) when a timeline point is saved

    /** A timeline point saved while it's happening (10, 25, HT, 60, 80): the moment to fetch that point's odds. */
    public record PointSaved(String gameId, String point, String kickoff, String home, String away, String score) {
        public PointSaved(String gameId, String point, String kickoff, String home, String away) {
            this(gameId, point, kickoff, home, away, null);
        }
    }

    @Autowired(required = false)
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    private com.sportsbettingassistant.settings.SettingsService settings;   // Live Refresh, picked in Settings

    @Autowired(required = false)
    public void setSettings(com.sportsbettingassistant.settings.SettingsService settings) {
        this.settings = settings;
    }

    /** Minutes between refreshes of followed games: Settings' Live Refresh, else goal-api.refresh-minutes. */
    int refreshMinutes() {
        return settings != null ? settings.liveRefreshMinutes() : refreshMinutes;
    }
    private String warning;

    public LiveService(GoalApiClient api, Environment env,
                       Store store,
                       @Value("${goal-api.refresh-minutes:5}") int refreshMinutes,
                       @Value("${goal-api.max-games:20}") int maxGames,
                       @Value("${goal-api.reserve-calls:50}") int reserve) {
        this.api = api;
        this.store = store;
        this.games = new Games(store);
        this.leagues = Binder.get(env).bind("goal-api.leagues", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        this.refreshMinutes = Math.max(1, refreshMinutes);
        this.maxGames = maxGames;
        this.reserve = reserve;
        load();
    }

    // ---------------------------------------------------------------- what the pages read

    /** Everything the pop-up and the list need. No API calls. */
    public synchronized Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", api.configured());
        out.put("refreshMinutes", refreshMinutes());
        out.put("maxGames", maxGames);
        out.put("reserveCalls", reserve);
        out.put("usage", api.usage());
        out.put("lastRefresh", Json.text(lastRefresh));
        out.put("listedAt", Json.text(lastList));
        out.put("watching", !active(Instant.now()).isEmpty());
        out.put("warning", warning);
        out.put("tracked", new ArrayList<>(tracked));
        out.put("following", following.stream().map(this::followed).flatMap(Optional::stream).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> f : fixtures.values()) {
            Map<String, Object> copy = new LinkedHashMap<>(f);
            copy.put("saved", snapshots.containsKey(String.valueOf(f.get("id"))));
            copy.put("eventId", eventId(String.valueOf(f.get("id"))));
            list.add(copy);
        }
        list.sort(Comparator.comparing(f -> String.valueOf(f.get("kickoff"))));
        out.put("fixtures", list);
        return out;
    }

    /** Reads a game's official events again (see refreshOfficial) and saves them; then the game as match() gives it. */
    public synchronized Map<String, Object> refreshEvents(String id) {
        if (!fixtures.containsKey(id)) {
            throw new IllegalArgumentException("No such game: " + id);
        }
        if (refreshOfficial(id)) {
            lastOfficial.put(id, Instant.now());
            save();
        }
        return match(id);
    }

    /** One game's summary and its latest stats (null if none yet). */
    public synchronized Map<String, Object> match(String id) {
        Map<String, Object> f0 = fixtures.get(id);
        if (f0 != null && GoalData.isFinished(f0) && !hasOfficial(id) && officialTried.add(id)) {
            if (refreshOfficial(id)) {   // a game that wasn't followed (or finished before): its goals and cards, 2 calls once
                save();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fixture", fixtures.get(id));
        out.put("tracked", tracked.contains(id));
        out.put("stats", stats.get(id));
        out.put("snapshots", snapshotList(id));
        out.put("events", timeline.getOrDefault(id, List.of()));
        out.put("lastRefresh", Json.text(lastRefresh));
        out.put("refreshMinutes", refreshMinutes());
        return out;
    }

    // ---------------------------------------------------------------- pop-up actions

    /** Today's games in our competitions: 1 call per competition, reused for 3 hours. */
    public synchronized Map<String, Object> loadFixtures(boolean force) {
        if (force || lastList == null || lastList.plus(LIST_TTL).isBefore(Instant.now())) {
            LocalDate today = LocalDate.now(UK);
            Map<String, String> params = Map.of("from", today.toString(), "to", today.toString());
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, String> l : leagues.entrySet()) {
                try {
                    for (Map<String, Object> f : GoalData.list(api.getJson("/leagues/" + l.getValue() + "/fixtures", params))) {
                        put(GoalData.summary(f, l.getKey()));
                    }
                } catch (RuntimeException e) {
                    failed.add(l.getKey());
                    log.warn("GOAL API fixtures for {} failed: {}", l.getKey(), e.getMessage());
                }
            }
            prune();
            lastList = Instant.now();
            warning = failed.isEmpty() ? null : "Couldn't load games for: " + String.join(", ", failed);
            save();
        }
        return state();
    }

    /** Saves the ticked games (unknown ids are ignored, at most max-games). Refreshes now if any is on. */
    public Map<String, Object> track(List<String> ids) {
        boolean refreshNow;
        synchronized (this) {
            tracked.clear();
            for (String id : ids) {
                if (fixtures.containsKey(id) && tracked.size() < maxGames) {
                    tracked.add(id);
                }
            }
            refreshNow = !active(Instant.now()).isEmpty();
            if (refreshNow) {
                lastRefresh = null;   // so the next tick refreshes straight away
            }
            save();
        }
        if (refreshNow) {
            Thread.startVirtualThread(this::tick);
        }
        return state();
    }

    /**
     * Follows a game live from the match page (its play button), even days ahead: on match day it's looked up in
     * GOAL API (1 call per competition and day) and ticked, then refreshed and saved at 30', HT, 60', 80' and FT.
     * A game GOAL API already lists is ticked straight away. eventId: The Odds API's id for the game.
     */
    public synchronized Map<String, Object> follow(String eventId) {
        long game = gameOf(eventId);
        String known = fixtureOf(game);
        if (known != null) {
            addTracked(known);
        } else if (following.add(game)) {
            resolveFollowing(Instant.now());
        }
        save();
        return state();
    }

    /** Stops following a game (from the match page): unticks it, or forgets it if it isn't listed yet. */
    public synchronized Map<String, Object> unfollow(String eventId) {
        games.game(Games.ODDS, eventId).ifPresent(game -> {
            following.remove(game);
            String known = fixtureOf(game);
            if (known != null) {
                tracked.remove(known);
            }
        });
        save();
        return state();
    }

    private void addTracked(String id) {
        if (tracked.size() < maxGames) {
            tracked.add(id);
        }
    }

    /** The game (games.id) an odds event is, for a competition GOAL API has; else a message saying why not. */
    private long gameOf(String eventId) {
        long game = games.game(Games.ODDS, eventId).orElseThrow(() -> new IllegalArgumentException(
                "This game isn't matched to GOAL API's teams yet (Refresh Odds, then try again)."));
        String tournament = tournamentOf(game);
        if (tournament == null || !leagues.containsKey(tournament)) {
            throw new IllegalArgumentException("No GOAL API league for this game's competition.");
        }
        return game;
    }

    /** Our tournament name ("epl") for a game's competition. */
    private String tournamentOf(long game) {
        return games.get(game).map(g -> Competitions.tournament(String.valueOf(g.get("competition")))).orElse(null);
    }

    /** GOAL API's listed game for one of our games, or null if it isn't listed (yet). */
    private String fixtureOf(long game) {
        return games.sourceId(game, Games.GOAL).filter(fixtures::containsKey).orElse(null);
    }

    /** The Odds API's id for a listed game (through the game both are), or null. */
    private String eventId(String fixtureId) {
        return games.game(Games.GOAL, fixtureId).flatMap(games::oddsEvent).orElse(null);
    }

    /** A followed game as the pop-up lists it: {gameId, eventId, tournament, kickoff, home, away}. */
    private Optional<Map<String, Object>> followed(long game) {
        return store.first("SELECT g.competition, g.kickoff_at, COALESCE(h.short_name, h.name), COALESCE(a.short_name, a.name) "
                + "FROM games g JOIN teams h ON h.id = g.home_team_id JOIN teams a ON a.id = g.away_team_id WHERE g.id = ?", rs -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("gameId", game);
                    w.put("eventId", games.oddsEvent(game).orElse(null));
                    w.put("tournament", Competitions.tournament(rs.getString(1)));
                    w.put("kickoff", rs.getString(2));
                    w.put("home", rs.getString(3));
                    w.put("away", rs.getString(4));
                    return w;
                }, game);
    }

    /**
     * Keeps a GOAL API game's summary and links it to its game (once: by its teams' GOAL API ids, else their names),
     * so the odds and the match page find it by id.
     */
    private void put(Map<String, Object> f) {
        String id = String.valueOf(f.get("id"));
        fixtures.put(id, f);
        link(f);
    }

    private void link(Map<String, Object> f) {
        String id = String.valueOf(f.get("id")), code = Competitions.CODES.get(String.valueOf(f.get("tournament")));
        try {
            if (code == null || games.game(Games.GOAL, id).isPresent()) {
                return;
            }
            Optional<Long> home = team(code, f, "home"), away = team(code, f, "away");
            if (home.isPresent() && away.isPresent()) {
                games.link(Games.GOAL, id, code, GoalData.text(f, "kickoff"), home.get(), away.get());
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't link GOAL API game {} to a game: {}", id, e.getMessage());
        }
    }

    private Optional<Long> team(String code, Map<String, Object> f, String side) {
        String goalId = GoalData.text(f, side + "TeamGoalId");
        Optional<Long> byId = goalId == null ? Optional.empty()
                : store.first("SELECT team_id FROM goal_teams WHERE goal_id = ?", rs -> rs.getLong(1), goalId);
        return byId.isPresent() ? byId : games.team(Games.GOAL, code, GoalData.text(f, side));
    }

    /** That day's games in a competition (1 call), kept and linked. */
    private void loadDay(String tournament, String league, String date) {
        for (Map<String, Object> g : GoalData.list(api.getJson("/fixtures/date/" + date, Map.of("leagueId", league)))) {
            put(GoalData.summary(g, tournament));
        }
    }

    /**
     * Ticks followed games that are on today (UK): their competition's games for the day are looked up once
     * (1 call each). Followed games more than a day past kick-off are dropped.
     */
    void resolveFollowing(Instant now) {
        following.removeIf(game -> {
            Instant ko = games.get(game).map(g -> GoalData.instant(g.get("kickoffAt"))).orElse(null);
            return ko == null || ko.isBefore(now.minus(Duration.ofDays(1)));
        });
        LocalDate today = LocalDate.ofInstant(now, UK);
        for (Long game : new ArrayList<>(following)) {
            Map<String, Object> g = games.get(game).orElse(null);
            String tournament = tournamentOf(game);
            if (g == null || tournament == null) {
                continue;
            }
            String date = LocalDate.ofInstant(GoalData.instant(g.get("kickoffAt")), UK).toString();
            if (!date.equals(today.toString())) {
                continue;
            }
            String league = leagues.get(tournament);
            if (league != null && lookedUp.add(league + "|" + date)) {
                try {
                    loadDay(tournament, league, date);
                } catch (RuntimeException e) {
                    lookedUp.remove(league + "|" + date);   // try again at the next refresh
                    log.warn("GOAL API games for followed {} failed: {}", tournament, e.getMessage());
                    continue;
                }
            }
            String id = fixtureOf(game);
            if (id != null) {
                addTracked(id);
                following.remove(game);
            }
        }
    }

    /**
     * Loads one game once, even a finished one: that day's games in its competition (1 call) to find it and get the
     * scores, then its stats (1 call), saved as HT and FT snapshots and kept 14 days. The game is ticked.
     * eventId: The Odds API's id for the game.
     */
    public synchronized Map<String, Object> replay(String eventId) {
        long game = gameOf(eventId);
        String tournament = tournamentOf(game);
        String date = LocalDate.ofInstant(GoalData.instant(games.get(game).orElseThrow().get("kickoffAt")), UK).toString();
        loadDay(tournament, leagues.get(tournament), date);
        String id = fixtureOf(game);
        if (id == null) {
            throw new IllegalArgumentException("Couldn't find this game on " + date + " in GOAL API.");
        }
        loadStats(id, fixtures.get(id));
        tracked.add(id);
        save();
        return match(id);
    }

    /** One game's stats (1 call), saved as its latest stats plus HT and FT points where it's got that far. */
    private void loadStats(String id, Map<String, Object> f) {
        Map<String, Object> body = api.getJson("/fixtures/" + id + "/statistics", Map.of());
        Map<String, Object> s = GoalData.computeStats(body);
        s.put("minute", f.get("minute"));
        s.put("final", GoalData.isFinished(f));
        stats.put(id, s);
        Map<String, Object> match = Json.map(Json.map(body.get("data")).get("match"));
        List<Map<String, Object>> firstHalf = Json.maps(match.get("firstHalf"));
        boolean pastHalfTime = GoalData.isFinished(f)
                || String.valueOf(f.get("period")).toUpperCase().matches(".*(HALF_TIME|SECOND|2ND).*");
        if (!firstHalf.isEmpty() && pastHalfTime && f.get("htHome") != null) {
            putSnapshot(id, "HT", f.get("htHome"), f.get("htAway"), GoalData.statsFromRows(firstHalf));
        }
        if (GoalData.isFinished(f)) {
            putSnapshot(id, "FT", f.get("homeScore"), f.get("awayScore"), GoalData.statsFromRows(Json.maps(match.get("fullTime"))));
            saveResult(id, f);
        }
    }

    /** A finished game's score in games (so the prediction log has it before Refresh Stats brings it). */
    private void saveResult(String id, Map<String, Object> f) {
        Long h = whole(f.get("homeScore")), a = whole(f.get("awayScore"));
        if (h == null || a == null) {
            return;
        }
        games.game(Games.GOAL, id).ifPresent(game -> store.update("UPDATE games SET status = 'FINISHED', home_goals = ?, "
                + "away_goals = ?, ht_home = COALESCE(?, ht_home), ht_away = COALESCE(?, ht_away) WHERE id = ?",
                h, a, whole(f.get("htHome")), whole(f.get("htAway")), game));
    }

    private static Long whole(Object o) {
        try {
            return o == null ? null : Long.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Post-Match for many games at once (Live Stats pop-up): for each competition and day, its games (1 call), then
     * each finished game's stats (1 call) saved as HT and FT points. Games that already have FT are skipped. No odds
     * (finished games have none). list: [{eventId, home, away}] (the names only for the message).
     * Returns {loaded, skipped, missing:[names], calls}.
     */
    public synchronized Map<String, Object> postMatch(List<Map<String, Object>> list) {
        int loaded = 0, skipped = 0, calls = 0;
        List<String> missing = new ArrayList<>();
        Set<String> days = new LinkedHashSet<>();
        for (Map<String, Object> g : list) {
            String name = Json.text(g.get("home")) + " v " + Json.text(g.get("away"));
            Optional<Long> game = games.game(Games.ODDS, Json.text(g.get("eventId")));
            String tournament = game.map(this::tournamentOf).orElse(null);
            String league = tournament == null ? null : leagues.get(tournament);
            Instant ko = game.flatMap(games::get).map(x -> GoalData.instant(x.get("kickoffAt"))).orElse(null);
            if (league == null || ko == null) {
                missing.add(name);
                continue;
            }
            String date = LocalDate.ofInstant(ko, UK).toString();
            if (fixtureOf(game.get()) == null && days.add(league + "|" + date)) {
                try {
                    calls++;
                    loadDay(tournament, league, date);
                } catch (RuntimeException e) {
                    log.warn("GOAL API games for {} on {} failed: {}", tournament, date, e.getMessage());
                }
            }
            String id = fixtureOf(game.get());
            if (id == null || !GoalData.isFinished(fixtures.get(id))) {
                missing.add(name);
                continue;
            }
            if (snapshots.getOrDefault(id, Map.of()).containsKey("FT")) {
                skipped++;
                continue;
            }
            try {
                calls++;
                loadStats(id, fixtures.get(id));
                loaded++;
            } catch (RuntimeException e) {
                log.warn("GOAL API stats for {} failed: {}", name, e.getMessage());
                missing.add(name);
            }
        }
        save();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loaded", loaded);
        out.put("skipped", skipped);
        out.put("missing", missing);
        out.put("calls", calls);
        return out;
    }

    private void putSnapshot(String id, String period, Object homeScore, Object awayScore, Map<String, Object> st) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period);
        snap.put("homeScore", homeScore);
        snap.put("awayScore", awayScore);
        snap.put("xg", st.get("xg"));
        snap.put("rows", st.get("rows"));
        snap.put("savedAt", Instant.now().toString());
        snapshots.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(period, snap);
    }

    /** A game's snapshots in timeline order. */
    private List<Map<String, Object>> snapshotList(String id) {
        Map<String, Map<String, Object>> byPeriod = snapshots.getOrDefault(id, Map.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String p : PERIODS) {
            if (byPeriod.containsKey(p)) {
                out.add(byPeriod.get(p));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the refresh

    @Scheduled(initialDelay = 15_000, fixedDelay = 10_000)   // checks often (free); calls GOAL API only when a refresh is due
    public synchronized void tick() {
        Instant now = Instant.now();
        if (!following.isEmpty()) {
            int before = following.size();
            resolveFollowing(now);
            if (following.size() != before) {
                save();
            }
        }
        List<Map<String, Object>> on = active(now);
        if (on.isEmpty()) {
            return;
        }
        if (refreshMinutes() <= 0) {   // Refresh Data Live: Off
            return;
        }
        if (lastRefresh != null && lastRefresh.plus(Duration.ofMinutes(refreshMinutes())).isAfter(now)) {
            return;
        }
        long left = api.remaining();
        int needed = (int) on.stream().map(f -> f.get("leagueId")).distinct().count() + on.size();
        if (left >= 0 && left - needed < reserve) {
            warning = "Paused: only " + left + " GOAL API calls left today (keeping " + reserve + " spare).";
            lastRefresh = now;
            save();
            return;
        }
        warning = null;
        // 1) Scores, minutes and status: one call per competition and day.
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();   // each game as it was, for the timeline
        for (Map<String, Object> f : on) {
            before.put(String.valueOf(f.get("id")), fixtures.get(String.valueOf(f.get("id"))));
        }
        Set<String> done = new LinkedHashSet<>();
        for (Map<String, Object> f : on) {
            String league = String.valueOf(f.get("leagueId")), date = String.valueOf(f.get("date"));
            if (done.add(league + "|" + date)) {
                try {
                    String tournament = String.valueOf(f.get("tournament"));
                    loadDay(tournament, league, date);
                } catch (RuntimeException e) {
                    warning = "Last refresh had errors: " + e.getMessage();
                    log.warn("GOAL API scores failed: {}", e.getMessage());
                }
            }
        }
        // 2) Match stats for each ticked game that is live, plus once more when it has just finished.
        for (Map<String, Object> f0 : on) {
            String id = String.valueOf(f0.get("id"));
            Map<String, Object> f = fixtures.getOrDefault(id, f0);
            boolean live = Json.yes(f.get("live"));
            boolean finishedNeedsFinal = GoalData.isFinished(f) && !(stats.get(id) != null && Json.yes(stats.get(id).get("final")));
            if (!live && !finishedNeedsFinal) {
                continue;
            }
            try {
                Map<String, Object> body = api.getJson("/fixtures/" + id + "/statistics", Map.of());
                Map<String, Object> s = GoalData.computeStats(body);
                s.put("minute", f.get("minute"));
                s.put("final", GoalData.isFinished(f));
                // GOAL API sometimes answers a live game with no stats at all for a refresh or two: keep the last
                // good ones (so the page doesn't go blank, and the next shots on target are counted from them)
                Map<String, Object> prev = stats.get(id);
                if (GoalData.rowCount(s) == 0 && GoalData.rowCount(prev) > 0) {
                    s.put("rows", prev.get("rows"));
                    s.put("xg", prev.get("xg"));
                    s.put("stale", true);
                    log.info("GOAL API sent no stats for {} at {}': kept the last ones.", id, f.get("minute"));
                }
                @SuppressWarnings("unchecked")
                Map<String, List<Map<String, Object>>> all = (Map<String, List<Map<String, Object>>>) s.remove("allPlayers");
                addEvents(id, before.get(id), f, stats.get(id), s, all);
                stats.put(id, s);
                saveTimelinePoints(id, f, body, s);
            } catch (RuntimeException e) {
                warning = "Last refresh had errors: " + e.getMessage();
                log.warn("GOAL API stats for {} failed: {}", id, e.getMessage());
            }
        }
        lastRefresh = now;
        save();
    }

    /**
     * Saves the timeline points a game has reached, once each: 30', 60' and 80' from the stats so far,
     * HT from the first-half stats once it's past half-time, FT from the full-time stats when it's finished.
     */
    void saveTimelinePoints(String id, Map<String, Object> f, Map<String, Object> body, Map<String, Object> s) {
        Map<String, Map<String, Object>> have = snapshots.getOrDefault(id, Map.of());
        Map<String, Object> match = Json.map(Json.map(body.get("data")).get("match"));
        boolean finished = GoalData.isFinished(f);
        boolean pastHalfTime = finished || String.valueOf(f.get("period")).toUpperCase().matches(".*(HALF_TIME|SECOND|2ND).*");
        List<Map<String, Object>> firstHalf = Json.maps(match.get("firstHalf"));
        boolean atTheBreak = !finished && String.valueOf(f.get("period")).toUpperCase().contains("HALF_TIME")
                || "HT".equalsIgnoreCase(String.valueOf(f.get("minute")));
        // At the break the match stats so far ARE the first half's, and GOAL API's separate first-half block can be
        // much thinner (no shots, no xG): so at the break the full stats; saved later in the 2nd half, the first-half
        // block (the full stats include the 2nd half by then). At the break a thin HT is replaced by a fuller one.
        Map<String, Object> htStats = atTheBreak ? s : firstHalf.isEmpty() ? null : GoalData.statsFromRows(firstHalf);
        boolean fuller = atTheBreak && have.containsKey("HT") && htStats != null
                && GoalData.rowCount(htStats) > GoalData.rowCount(have.get("HT"));
        if (pastHalfTime && htStats != null && f.get("htHome") != null && (!have.containsKey("HT") || fuller)) {
            boolean first = !have.containsKey("HT");
            putSnapshot(id, "HT", f.get("htHome"), f.get("htAway"), htStats);
            if (atTheBreak && first) {
                announce(id, "HT", f);   // the half-time odds only if it's half-time now (not saved late in the 2nd half)
            }
        }
        if (finished && !have.containsKey("FT")) {
            putSnapshot(id, "FT", f.get("homeScore"), f.get("awayScore"), GoalData.statsFromRows(Json.maps(match.get("fullTime"))));
            saveResult(id, f);
        }
        String point = MatchTimeline.pointFor(f);
        if (point != null && !snapshots.getOrDefault(id, Map.of()).containsKey(point)) {
            putSnapshot(id, point, f.get("homeScore"), f.get("awayScore"), s);
            announce(id, point, f);
        }
    }

    private void announce(String id, String point, Map<String, Object> f) {
        if (events != null) {
            events.publishEvent(new PointSaved(id, point, Json.text(f.get("kickoff")), Json.text(f.get("home")), Json.text(f.get("away")),
                    Json.text(f.get("homeScore")) + "-" + Json.text(f.get("awayScore"))));
        }
    }

    /** Ticked games that are about to start or being played (and not finished with final stats). */
    private List<Map<String, Object>> active(Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : tracked) {
            Map<String, Object> f = fixtures.get(id);
            if (f == null) {
                continue;
            }
            Instant ko = GoalData.instant(f.get("kickoff"));
            if (ko == null || now.isBefore(ko.minus(BEFORE)) || now.isAfter(ko.plus(AFTER))) {
                continue;
            }
            if (GoalData.isOff(f)) {
                continue;
            }
            Map<String, Object> s = stats.get(id);
            if (GoalData.isFinished(f) && s != null && Json.yes(s.get("final"))) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    // ---------------------------------------------------------------- timeline (goals, cards, shots on target...)

    /**
     * Adds what happened since the last refresh to the game's timeline: kick-off, goals (with the scorer when a
     * player's goal count went up), yellow and red cards and penalties (the team's counts going up), shots on target
     * (a player's count going up, not counting goals), half time, second half and full time. GOAL API gives no event
     * times, so each event gets the minute of the refresh that saw it (within a minute at a 1-minute refresh).
     */
    void addEvents(String id, Map<String, Object> before, Map<String, Object> f, Map<String, Object> prevStats,
                   Map<String, Object> s, Map<String, List<Map<String, Object>>> players) {
        List<Map<String, Object>> list = timeline.computeIfAbsent(id, k -> new ArrayList<>());
        Map<String, List<Map<String, Object>>> prevPlayers = lastPlayers.get(id);
        if (prevPlayers == null && prevStats != null) {   // after a restart: the saved top shooters
            prevPlayers = new LinkedHashMap<>();
            prevPlayers.put("home", Json.maps(prevStats.get("homePlayers")));
            prevPlayers.put("away", Json.maps(prevStats.get("awayPlayers")));
        }
        if (players != null) {
            lastPlayers.put(id, players);
        }
        List<Map<String, Object>> found = MatchTimeline.events(before, f, prevStats, s, prevPlayers, players, list);
        found.forEach(e -> e.put("source", "seen"));
        String[] gk = keepers.get(id);   // a save: that side's keeper (from the line-ups)
        if (gk != null) {
            found.stream().filter(e -> "save".equals(e.get("kind")) && e.get("player") == null)
                    .forEach(e -> e.put("player", "home".equals(e.get("side")) ? gk[0] : gk[1]));
        }
        list.addAll(found);
        boolean big = found.stream().anyMatch(e -> List.of("goal", "yellow", "red", "penalty", "ft").contains(e.get("kind")));
        // GOAL API's own goals, cards, substitutions and commentary (4 calls): after a goal or card, and every 5
        // minutes anyway (substitutions and commentary moments aren't seen in the stats)
        Instant last = lastOfficial.computeIfAbsent(id, k -> Instant.now());   // the first: 5 minutes from now
        if (big || last.plus(OFFICIAL_EVERY).isBefore(Instant.now())) {
            refreshOfficial(id);
            lastOfficial.put(id, Instant.now());
        }
    }

    // ---------------------------------------------------------------- highlights video

    static final Duration VIDEO_RECHECK = Duration.ofHours(6);

    /**
     * A finished game's highlights video from GOAL API (/videos/match/{id}, 1 call): {url, title}, or {} if there's
     * none (yet). Videos can turn up days later, so "none" is checked again after 6 hours; one found is kept.
     * Games not finished (or not known) get {} with no call.
     */
    public Map<String, Object> highlights(String id) {
        Map<String, Object> f;
        synchronized (this) {
            f = fixtures.get(id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<String[]> saved = store.first("SELECT url, title, checked_at FROM live_videos WHERE game_id = ?",
                rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, id);
        if (saved.isPresent() && saved.get()[0] != null && !saved.get()[0].isBlank()) {
            out.put("url", saved.get()[0]);
            out.put("title", saved.get()[1]);
            return out;
        }
        if (f == null || !GoalData.isFinished(f)) {
            return out;
        }
        Instant checked = saved.map(x -> GoalData.instant(x[2])).orElse(null);
        if (checked != null && checked.plus(VIDEO_RECHECK).isAfter(Instant.now())) {
            return out;
        }
        String url = null, title = null;
        try {
            for (Map<String, Object> v : Json.maps(api.getJson("/videos/match/" + id, Map.of()).get("data"))) {
                String u = Json.text(v.get("url"));
                if (u != null && u.startsWith("https://")) {
                    url = u;
                    title = Json.text(v.get("title"));
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("GOAL API video for {} failed: {}", id, e.getMessage());
            return out;
        }
        store.update("INSERT OR REPLACE INTO live_videos (game_id, url, title, checked_at) VALUES (?, ?, ?, ?)",
                id, url, title, Instant.now().toString());
        if (url != null) {
            out.put("url", url);
            out.put("title", title);
        }
        return out;
    }

    private boolean hasOfficial(String id) {
        return timeline.getOrDefault(id, List.of()).stream().anyMatch(e -> "api".equals(e.get("source")));
    }

    /**
     * Swaps the goals and cards the refreshes spotted for GOAL API's own (/fixtures/{id}/events and /cards: real
     * minutes, scorers, assists, penalties). Shots on target, kick-off, HT, 2H and FT stay, and a penalty spotted
     * stays unless an official penalty goal for that side is within 5 minutes (a missed one). Returns true if it got them.
     */
    boolean refreshOfficial(String id) {
        List<Map<String, Object>> official = new ArrayList<>();
        try {
            official.addAll(MatchTimeline.officialGoals(api.getJson("/fixtures/" + id + "/events", Map.of())));
            official.addAll(MatchTimeline.officialCards(api.getJson("/fixtures/" + id + "/cards", Map.of())));
            official.addAll(MatchTimeline.officialSubs(api.getJson("/fixtures/" + id + "/substitutions", Map.of())));
            if (!keepers.containsKey(id)) {   // the line-ups, once: each side's goalkeeper (for the saves)
                String[] gk = MatchTimeline.keepersOf(api.getJson("/fixtures/" + id + "/lineups", Map.of()));
                if (gk[0] != null || gk[1] != null) {
                    keepers.put(id, gk);
                }
            }
            Map<String, Object> fx = fixtures.get(id);
            official.addAll(MatchTimeline.commentary(api.getJson("/fixtures/" + id + "/commentary", Map.of()),
                    fx == null ? null : Json.text(fx.get("home")), fx == null ? null : Json.text(fx.get("away"))));
        } catch (RuntimeException e) {
            log.warn("GOAL API events for {} failed: {}", id, e.getMessage());
            return false;
        }
        List<Map<String, Object>> list = timeline.computeIfAbsent(id, k -> new ArrayList<>());
        boolean haveOfficial = !official.isEmpty();
        list.removeIf(e -> "api".equals(e.get("source")));
        if (haveOfficial) {
            list.removeIf(e -> List.of("goal", "yellow", "red").contains(e.get("kind")));
            // a penalty spotted from the team's count that the commentary names (same side, within 2')
            list.removeIf(e -> "penalty".equals(e.get("kind")) && e.get("player") == null && official.stream().anyMatch(o -> "penalty".equals(o.get("kind"))
                    && String.valueOf(o.get("side")).equals(e.get("side"))
                    && Math.abs(GoalData.num(Json.text(o.get("elapsed"))) - GoalData.num(Json.text(e.get("elapsed")))) <= 2));
            // a shot on target spotted from the player stats that the commentary has too (same side, within 2')
            list.removeIf(e -> "shot".equals(e.get("kind")) && official.stream().anyMatch(o -> "shot".equals(o.get("kind"))
                    && String.valueOf(o.get("side")).equals(e.get("side"))
                    && Math.abs(GoalData.num(Json.text(o.get("elapsed"))) - GoalData.num(Json.text(e.get("elapsed")))) <= 2));
            list.removeIf(e -> "penalty".equals(e.get("kind")) && official.stream().anyMatch(o -> "goal".equals(o.get("kind"))
                    && "Penalty".equalsIgnoreCase(String.valueOf(o.get("info"))) && String.valueOf(o.get("side")).equals(e.get("side"))
                    && Math.abs(GoalData.num(Json.text(o.get("elapsed"))) - GoalData.num(Json.text(e.get("elapsed")))) <= 5));
        }
        list.addAll(official);
        list.sort(Comparator.comparingInt(e -> GoalData.num(Json.text(e.get("order")))));
        return true;
    }

    /** Forget games that kicked off more than a day ago (14 days for games with snapshots). */
    private void prune() {
        Instant now = Instant.now();
        fixtures.values().removeIf(f -> {
            Instant ko = GoalData.instant(f.get("kickoff"));
            Duration keep = snapshots.containsKey(String.valueOf(f.get("id"))) ? KEEP_SNAPSHOTS : Duration.ofDays(1);
            return ko != null && ko.isBefore(now.minus(keep));
        });
        tracked.retainAll(fixtures.keySet());
        stats.keySet().retainAll(fixtures.keySet());
        snapshots.keySet().retainAll(fixtures.keySet());
        timeline.keySet().retainAll(fixtures.keySet());
        lastPlayers.keySet().retainAll(fixtures.keySet());
    }

    // ---------------------------------------------------------------- saved copy

    /** Writes the games, ticks, latest stats and calls left to the live tables (small: rewritten each time). */
    private void save() {
        try {
            store.transaction(() -> {
                for (String table : new String[] {"live_timeline", "live_snapshots", "live_players", "live_stat_rows", "live_stats", "live_games"}) {
                    store.update("DELETE FROM " + table);
                }
                List<String> ticks = new ArrayList<>(tracked);
                for (Map<String, Object> f : fixtures.values()) {
                    String id = String.valueOf(f.get("id"));
                    int tick = ticks.indexOf(id);
                    store.update("INSERT OR REPLACE INTO live_games (id, tournament, league_id, date, kickoff, home, away, "
                            + "home_score, away_score, status, live, minute, elapsed, extra, period, updated_at, ticked) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            id, GoalData.text(f, "tournament"), GoalData.text(f, "leagueId"), GoalData.text(f, "date"), GoalData.text(f, "kickoff"),
                            GoalData.text(f, "home"), GoalData.text(f, "away"), GoalData.text(f, "homeScore"), GoalData.text(f, "awayScore"), GoalData.text(f, "status"),
                            Json.yes(f.get("live")), GoalData.text(f, "minute"), GoalData.text(f, "elapsed"), GoalData.text(f, "extra"),
                            GoalData.text(f, "period"), GoalData.text(f, "updatedAt"), tick < 0 ? null : tick + 1);
                }
                stats.forEach((id, st) -> {
                    List<?> xg = st.get("xg") instanceof List<?> l && l.size() == 2 ? l : null;
                    store.update("INSERT OR REPLACE INTO live_stats (game_id, xg_home, xg_away, minute, final, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", id, xg == null ? null : xg.get(0), xg == null ? null : xg.get(1),
                            GoalData.text(st, "minute"), Json.yes(st.get("final")), GoalData.text(st, "updatedAt"));
                    int position = 0;
                    for (Map<String, Object> r : Json.maps(st.get("rows"))) {
                        store.update("INSERT INTO live_stat_rows (game_id, position, label, home, away) VALUES (?, ?, ?, ?, ?)",
                                id, position++, GoalData.text(r, "label"), GoalData.text(r, "home"), GoalData.text(r, "away"));
                    }
                    for (String side : new String[] {"home", "away"}) {
                        int n = 0;
                        for (Map<String, Object> pl : Json.maps(st.get(side + "Players"))) {
                            store.update("INSERT INTO live_players (game_id, side, position, name, shots, on_target, goals) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, side, n++, GoalData.text(pl, "name"),
                                    pl.get("shots"), pl.get("onTarget"), pl.get("goals"));
                        }
                    }
                });
                snapshots.forEach((id, byPeriod) -> byPeriod.forEach((period, snap) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("xg", snap.get("xg"));
                    body.put("rows", snap.get("rows"));
                    store.update("INSERT INTO live_snapshots (game_id, period, home_score, away_score, stats, saved_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", id, period, GoalData.text(snap, "homeScore"), GoalData.text(snap, "awayScore"),
                            Json.write(body), GoalData.text(snap, "savedAt"));
                }));
                timeline.forEach((id, list) -> {
                    int seq = 0;
                    for (Map<String, Object> e : list) {
                        store.update("INSERT INTO live_timeline (game_id, seq, kind, side, player, assist, info, score, minute, "
                                + "half, elapsed, sort_order, source, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, seq++,
                                GoalData.text(e, "kind"), GoalData.text(e, "side"), GoalData.text(e, "player"), GoalData.text(e, "assist"), GoalData.text(e, "info"),
                                GoalData.text(e, "score"), GoalData.text(e, "minute"), e.get("half"), e.get("elapsed"), e.get("order"),
                                GoalData.text(e, "source"), GoalData.text(e, "at"));
                    }
                });
                store.update("DELETE FROM live_follow");
                for (Long game : following) {
                    store.update("INSERT OR REPLACE INTO live_follow (game_id) VALUES (?)", game);
                }
                remember("live", lastRefresh);
                remember("live-list", lastList);
                if (!api.usage().isEmpty()) {
                    store.saveUsage("goal-api", api.usage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Couldn't save Live Stats: {}", e.getMessage());
        }
    }

    private void remember(String name, Instant when) {
        if (when == null) {
            store.update("DELETE FROM refreshes WHERE name = ?", name);
        } else {
            store.markRefreshed(name, when);
        }
    }

    /** Reads the live tables back (after a restart). */
    private void load() {
        for (Map<String, Object> f : store.query("SELECT * FROM live_games ORDER BY kickoff, id", rs -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", rs.getString("id"));
            g.put("tournament", rs.getString("tournament"));
            g.put("leagueId", rs.getString("league_id"));
            g.put("date", rs.getString("date"));
            g.put("kickoff", rs.getString("kickoff"));
            g.put("home", rs.getString("home"));
            g.put("away", rs.getString("away"));
            g.put("homeScore", rs.getString("home_score"));
            g.put("awayScore", rs.getString("away_score"));
            g.put("status", rs.getString("status"));
            g.put("live", rs.getInt("live") == 1);
            g.put("minute", rs.getString("minute"));
            g.put("elapsed", rs.getString("elapsed"));
            g.put("extra", rs.getString("extra"));
            g.put("period", rs.getString("period"));
            g.put("updatedAt", rs.getString("updated_at"));
            return g;
        })) {
            put(f);   // (linked here too: games saved before games had ids)
        }
        tracked.addAll(store.query("SELECT id FROM live_games WHERE ticked IS NOT NULL ORDER BY ticked", rs -> rs.getString(1)));
        for (Map<String, Object> st : store.query("SELECT * FROM live_stats", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("game_id"));
            Double h = Store.doubleOrNull(rs, "xg_home"), a = Store.doubleOrNull(rs, "xg_away");
            if (h != null && a != null) {
                m.put("xg", List.of(h, a));
            }
            m.put("minute", rs.getString("minute"));
            m.put("final", rs.getInt("final") == 1);
            m.put("updatedAt", rs.getString("updated_at"));
            return m;
        })) {
            String id = String.valueOf(st.remove("id"));
            st.put("rows", store.query("SELECT label, home, away FROM live_stat_rows WHERE game_id = ? ORDER BY position",
                    rs -> GoalData.row(rs.getString(1), rs.getString(2), rs.getString(3)), id));
            for (String side : new String[] {"home", "away"}) {
                st.put(side + "Players", store.query("SELECT * FROM live_players WHERE game_id = ? AND side = ? ORDER BY position",
                        rs -> {
                            Map<String, Object> pl = new LinkedHashMap<>();
                            pl.put("name", rs.getString("name"));
                            pl.put("shots", rs.getInt("shots"));
                            pl.put("onTarget", rs.getInt("on_target"));
                            pl.put("goals", rs.getInt("goals"));
                            return pl;
                        }, id, side));
            }
            stats.put(id, st);
        }
        for (Map<String, Object> snap : store.query("SELECT * FROM live_snapshots", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("game_id"));
            m.put("period", rs.getString("period"));
            m.put("homeScore", rs.getString("home_score"));
            m.put("awayScore", rs.getString("away_score"));
            Map<String, Object> body = Json.parse(rs.getString("stats"));
            m.put("xg", body.get("xg"));
            m.put("rows", body.get("rows"));
            m.put("savedAt", rs.getString("saved_at"));
            return m;
        })) {
            String id = String.valueOf(snap.remove("id"));
            snapshots.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(String.valueOf(snap.get("period")), snap);
        }
        for (Map<String, Object> e : store.query("SELECT * FROM live_timeline ORDER BY game_id, seq", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("game_id"));
            m.put("kind", rs.getString("kind"));
            m.put("side", rs.getString("side"));
            m.put("player", rs.getString("player"));
            m.put("assist", rs.getString("assist"));
            m.put("info", rs.getString("info"));
            m.put("source", rs.getString("source"));
            m.put("score", rs.getString("score"));
            m.put("minute", rs.getString("minute"));
            m.put("half", rs.getInt("half"));
            m.put("elapsed", rs.getInt("elapsed"));
            m.put("order", rs.getInt("sort_order"));
            m.put("at", rs.getString("at"));
            return m;
        })) {
            timeline.computeIfAbsent(String.valueOf(e.remove("id")), k -> new ArrayList<>()).add(e);
        }
        following.addAll(store.query("SELECT f.game_id FROM live_follow f JOIN games g ON g.id = f.game_id ORDER BY g.kickoff_at",
                rs -> rs.getLong(1)));
        lastRefresh = store.refreshedAt("live").orElse(null);
        lastList = store.refreshedAt("live-list").orElse(null);
        store.usage("goal-api").ifPresent(api::restoreUsage);
        prune();
    }

}
