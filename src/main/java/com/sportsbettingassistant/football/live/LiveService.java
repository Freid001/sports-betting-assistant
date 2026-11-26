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
 * Snapshots: a game's stats at points in the match ('25', 'HT', '60', '80', 'FT') for the match page's timeline.
 * {@link #replay} loads a past game once (2 calls) as HT and FT snapshots; games with snapshots are kept 14 days.
 */
@Service
public class LiveService {

    private static final Logger log = LoggerFactory.getLogger(LiveService.class);
    private static final ZoneId UK = ZoneId.of("Europe/London");
    static final double XG_INSIDE = 0.12, XG_OUTSIDE = 0.035, XG_PENALTY = 0.76;
    /** How long the list of upcoming games is reused before the pop-up fetches it again. */
    private static final Duration LIST_TTL = Duration.ofHours(3);
    /** A ticked game is watched from just before kick-off until it finishes (or 3 hours after kick-off at most). */
    private static final Duration BEFORE = Duration.ofMinutes(2), AFTER = Duration.ofHours(3);
    /** Timeline order of snapshots. */
    static final List<String> PERIODS = List.of("10", "25", "HT", "60", "80", "FT");
    /** How long games with snapshots are kept (other games: 1 day after kick-off). */
    private static final Duration KEEP_SNAPSHOTS = Duration.ofDays(14);

    private final GoalApiClient api;
    private final Store store;
    private final Map<String, String> leagues;   // our tournament key -> GOAL API league id
    private final int refreshMinutes, maxGames, reserve;

    private final Map<String, Map<String, Object>> fixtures = new LinkedHashMap<>();  // id -> game summary
    private final Set<String> tracked = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> stats = new LinkedHashMap<>();     // id -> latest stats
    private final Map<String, Map<String, Map<String, Object>>> snapshots = new LinkedHashMap<>();  // id -> period -> snapshot
    /** What happened in each followed game, worked out refresh by refresh (goals, cards, shots on target...). */
    private final Map<String, List<Map<String, Object>>> timeline = new LinkedHashMap<>();
    /** Every player with a shot in the last refresh (side -> players), to see who scored or hit the target since. */
    private final Map<String, Map<String, List<Map<String, Object>>>> lastPlayers = new LinkedHashMap<>();
    /** Games picked to follow before GOAL API lists them: {tournament, kickoff, home, away}. Ticked on match day. */
    private final List<Map<String, Object>> following = new ArrayList<>();
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
        out.put("following", new ArrayList<>(following));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> f : fixtures.values()) {
            Map<String, Object> copy = new LinkedHashMap<>(f);
            copy.put("saved", snapshots.containsKey(String.valueOf(f.get("id"))));
            list.add(copy);
        }
        list.sort(Comparator.comparing(f -> String.valueOf(f.get("kickoff"))));
        out.put("fixtures", list);
        return out;
    }

    /** One game's summary and its latest stats (null if none yet). */
    public synchronized Map<String, Object> match(String id) {
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
                    for (Map<String, Object> f : list(api.getJson("/leagues/" + l.getValue() + "/fixtures", params))) {
                        Map<String, Object> s = summary(f, l.getKey());
                        fixtures.put(String.valueOf(s.get("id")), s);
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
     * GOAL API (1 call per competition and day) and ticked, then refreshed and saved at 10', 25', HT, 60', 80' and FT.
     * A game GOAL API already lists is ticked straight away.
     */
    public synchronized Map<String, Object> follow(String tournament, String kickoff, String home, String away) {
        if (!leagues.containsKey(tournament)) {
            throw new IllegalArgumentException("No GOAL API league for " + tournament);
        }
        if (instant(kickoff) == null) {
            throw new IllegalArgumentException("Bad kick-off time: " + kickoff);
        }
        String known = findFixture(tournament, kickoff, home, away);
        if (known != null) {
            addTracked(known);
        } else if (following.stream().noneMatch(w -> sameGame(w, tournament, kickoff, home, away))) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("tournament", tournament);
            w.put("kickoff", kickoff);
            w.put("home", home);
            w.put("away", away);
            following.add(w);
            resolveFollowing(Instant.now());
        }
        save();
        return state();
    }

    /** Stops following a game (from the match page): unticks it, or forgets it if it isn't listed yet. */
    public synchronized Map<String, Object> unfollow(String tournament, String kickoff, String home, String away) {
        following.removeIf(w -> sameGame(w, tournament, kickoff, home, away));
        String known = findFixture(tournament, kickoff, home, away);
        if (known != null) {
            tracked.remove(known);
        }
        save();
        return state();
    }

    private void addTracked(String id) {
        if (tracked.size() < maxGames) {
            tracked.add(id);
        }
    }

    private static boolean sameGame(Map<String, Object> w, String tournament, String kickoff, String home, String away) {
        return tournament.equals(w.get("tournament")) && near(w.get("kickoff"), kickoff)
                && similar(w.get("home"), home) + similar(w.get("away"), away) > 3;
    }

    /** A listed game for this match (same competition, kick-off within 30 minutes, similar names), or null. */
    private String findFixture(String tournament, String kickoff, String home, String away) {
        String best = null;
        int bestScore = 3;
        for (Map<String, Object> f : fixtures.values()) {
            if (!tournament.equals(f.get("tournament")) || !near(f.get("kickoff"), kickoff)) {
                continue;
            }
            int score = similar(f.get("home"), home) + similar(f.get("away"), away);
            if (score > bestScore) {
                best = String.valueOf(f.get("id"));
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Ticks followed games that are on today (UK): their competition's games for the day are looked up once
     * (1 call each). Followed games more than a day past kick-off are dropped.
     */
    void resolveFollowing(Instant now) {
        following.removeIf(w -> {
            Instant ko = instant(w.get("kickoff"));
            return ko == null || ko.isBefore(now.minus(Duration.ofDays(1)));
        });
        LocalDate today = LocalDate.ofInstant(now, UK);
        for (Map<String, Object> w : new ArrayList<>(following)) {
            String tournament = String.valueOf(w.get("tournament")), kickoff = String.valueOf(w.get("kickoff"));
            String date = LocalDate.ofInstant(instant(kickoff), UK).toString();
            if (!date.equals(today.toString())) {
                continue;
            }
            String league = leagues.get(tournament);
            if (league != null && lookedUp.add(league + "|" + date)) {
                try {
                    for (Map<String, Object> g : list(api.getJson("/fixtures/date/" + date, Map.of("leagueId", league)))) {
                        Map<String, Object> f = summary(g, tournament);
                        fixtures.put(String.valueOf(f.get("id")), f);
                    }
                } catch (RuntimeException e) {
                    lookedUp.remove(league + "|" + date);   // try again at the next refresh
                    log.warn("GOAL API games for followed {} failed: {}", tournament, e.getMessage());
                    continue;
                }
            }
            String id = findFixture(tournament, kickoff, String.valueOf(w.get("home")), String.valueOf(w.get("away")));
            if (id != null) {
                addTracked(id);
                following.remove(w);
            }
        }
    }

    /**
     * Loads one game once, even a finished one: that day's games in its competition (1 call) to find it and get the
     * scores, then its stats (1 call), saved as HT and FT snapshots and kept 14 days. The game is ticked.
     * kickoff (ISO) is optional; home and away are matched loosely (e.g. "Tottenham Hotspur" finds "Tottenham").
     */
    public synchronized Map<String, Object> replay(String tournament, String date, String kickoff, String home, String away) {
        String league = leagues.get(tournament);
        if (league == null) {
            throw new IllegalArgumentException("No GOAL API league for " + tournament);
        }
        Map<String, Object> best = null;
        int bestScore = 3;   // both names must at least partly match
        for (Map<String, Object> g : list(api.getJson("/fixtures/date/" + date, Map.of("leagueId", league)))) {
            Map<String, Object> f = summary(g, tournament);
            if (kickoff != null && !kickoff.isBlank() && !near(f.get("kickoff"), kickoff)) {
                continue;
            }
            int score = similar(f.get("home"), home) + similar(f.get("away"), away);
            if (score > bestScore) {
                best = f;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Couldn't find " + home + " v " + away + " on " + date + " in GOAL API.");
        }
        String id = String.valueOf(best.get("id"));
        fixtures.put(id, best);
        loadStats(id, best);
        tracked.add(id);
        save();
        return match(id);
    }

    /** One game's stats (1 call), saved as its latest stats plus HT and FT points where it's got that far. */
    private void loadStats(String id, Map<String, Object> f) {
        Map<String, Object> body = api.getJson("/fixtures/" + id + "/statistics", Map.of());
        Map<String, Object> s = computeStats(body);
        s.put("minute", f.get("minute"));
        s.put("final", isFinished(f));
        stats.put(id, s);
        Map<String, Object> match = Json.map(Json.map(body.get("data")).get("match"));
        List<Map<String, Object>> firstHalf = Json.maps(match.get("firstHalf"));
        boolean pastHalfTime = isFinished(f)
                || String.valueOf(f.get("period")).toUpperCase().matches(".*(HALF_TIME|SECOND|2ND).*");
        if (!firstHalf.isEmpty() && pastHalfTime && f.get("htHome") != null) {
            putSnapshot(id, "HT", f.get("htHome"), f.get("htAway"), statsFromRows(firstHalf));
        }
        if (isFinished(f)) {
            putSnapshot(id, "FT", f.get("homeScore"), f.get("awayScore"), statsFromRows(Json.maps(match.get("fullTime"))));
        }
    }

    /**
     * Post-Match for many games at once (Live Stats pop-up): for each competition and day, its games (1 call), then
     * each finished game's stats (1 call) saved as HT and FT points. Games that already have FT are skipped. No odds
     * (finished games have none). games: [{tournament, kickoff, home, away}].
     * Returns {loaded, skipped, missing:[names], calls}.
     */
    public synchronized Map<String, Object> postMatch(List<Map<String, Object>> games) {
        int loaded = 0, skipped = 0, calls = 0;
        List<String> missing = new ArrayList<>();
        Map<String, List<Map<String, Object>>> days = new LinkedHashMap<>();
        for (Map<String, Object> g : games) {
            String tournament = Json.text(g.get("tournament")), kickoff = Json.text(g.get("kickoff"));
            String home = Json.text(g.get("home")), away = Json.text(g.get("away"));
            String name = home + " v " + away;
            String league = tournament == null ? null : leagues.get(tournament);
            Instant ko = instant(kickoff);
            if (league == null || ko == null || home == null || away == null) {
                missing.add(name);
                continue;
            }
            String date = LocalDate.ofInstant(ko, UK).toString();
            List<Map<String, Object>> day = days.get(league + "|" + date);
            if (day == null) {
                try {
                    calls++;
                    day = new ArrayList<>();
                    for (Map<String, Object> x : list(api.getJson("/fixtures/date/" + date, Map.of("leagueId", league)))) {
                        Map<String, Object> f = summary(x, tournament);
                        fixtures.put(String.valueOf(f.get("id")), f);
                        day.add(f);
                    }
                } catch (RuntimeException e) {
                    log.warn("GOAL API games for {} on {} failed: {}", tournament, date, e.getMessage());
                    day = List.of();
                }
                days.put(league + "|" + date, day);
            }
            Map<String, Object> best = null;
            int bestScore = 3;
            for (Map<String, Object> f : day) {
                int score = near(f.get("kickoff"), kickoff) ? similar(f.get("home"), home) + similar(f.get("away"), away) : 0;
                if (score > bestScore) {
                    best = f;
                    bestScore = score;
                }
            }
            if (best == null || !isFinished(best)) {
                missing.add(name);
                continue;
            }
            String id = String.valueOf(best.get("id"));
            if (snapshots.getOrDefault(id, Map.of()).containsKey("FT")) {
                skipped++;
                continue;
            }
            try {
                calls++;
                loadStats(id, best);
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

    /** Within 30 minutes of each other (ISO times). */
    private static boolean near(Object a, String b) {
        Instant x = instant(a), y = instant(b);
        return x != null && y != null && Duration.between(x, y).abs().compareTo(Duration.ofMinutes(30)) <= 0;
    }

    /** How alike two team names are: 3 same, 2 one inside the other, 1 same first word (4+ letters), else 0. */
    public static int similar(Object a, Object b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.equals(y)) {
            return 3;
        }
        if (x.contains(y) || y.contains(x)) {
            return 2;
        }
        String fx = x.split(" ")[0], fy = y.split(" ")[0];
        return fx.equals(fy) && fx.length() >= 4 ? 1 : 0;
    }

    /**
     * A team name for matching: no accents (ø, æ, ß too), no club words (FC, CP, KV, AS...), short forms written out
     * (Utd, Atl., PSG), so "Sporting CP" v "Manchester Utd" matches "Sporting Lisbon" v "Manchester United".
     */
    static String norm(Object o) {
        if (o == null) {
            return "";
        }
        // names saved before the JSON reader decoded \\u escapes read e.g. "Atlu00e9tico": put the letter back
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("u(0[0-9a-fA-F]{3})").matcher(String.valueOf(o));
        StringBuilder fixed = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(fixed, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(fixed);
        String s = fixed.toString().replace("\u00f8", "o").replace("\u00d8", "O").replace("\u00e6", "ae").replace("\u00c6", "Ae")
                .replace("\u00df", "ss").replace("\u0142", "l").replace("\u0141", "L").replace("\u0111", "d").replace("\u0131", "i");
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        s = (" " + s + " ").replaceAll(" utd ", " united ").replaceAll(" atl ", " atletico ")
                .replaceAll(" psg ", " paris saint germain ").replaceAll(" man ", " manchester ");
        return s.replaceAll("\\b(fc|afc|cf|cp|kv|fk|sk|sc|ac|as|ssc|bk|if|sv|club|de)\\b", " ").replaceAll("\\s+", " ").trim();
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
                    for (Map<String, Object> g : list(api.getJson("/fixtures/date/" + date, Map.of("leagueId", league)))) {
                        Map<String, Object> s = summary(g, tournament);
                        fixtures.put(String.valueOf(s.get("id")), s);
                    }
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
            boolean finishedNeedsFinal = isFinished(f) && !(stats.get(id) != null && Json.yes(stats.get(id).get("final")));
            if (!live && !finishedNeedsFinal) {
                continue;
            }
            try {
                Map<String, Object> body = api.getJson("/fixtures/" + id + "/statistics", Map.of());
                Map<String, Object> s = computeStats(body);
                s.put("minute", f.get("minute"));
                s.put("final", isFinished(f));
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
     * Saves the timeline points a game has reached, once each: 10', 25', 60' and 80' from the stats so far,
     * HT from the first-half stats once it's past half-time, FT from the full-time stats when it's finished.
     */
    void saveTimelinePoints(String id, Map<String, Object> f, Map<String, Object> body, Map<String, Object> s) {
        Map<String, Map<String, Object>> have = snapshots.getOrDefault(id, Map.of());
        Map<String, Object> match = Json.map(Json.map(body.get("data")).get("match"));
        boolean finished = isFinished(f);
        boolean pastHalfTime = finished || String.valueOf(f.get("period")).toUpperCase().matches(".*(HALF_TIME|SECOND|2ND).*");
        List<Map<String, Object>> firstHalf = Json.maps(match.get("firstHalf"));
        if (pastHalfTime && !have.containsKey("HT") && !firstHalf.isEmpty() && f.get("htHome") != null) {
            putSnapshot(id, "HT", f.get("htHome"), f.get("htAway"), statsFromRows(firstHalf));
            boolean atTheBreak = !finished && String.valueOf(f.get("period")).toUpperCase().contains("HALF_TIME")
                    || "HT".equalsIgnoreCase(String.valueOf(f.get("minute")));
            if (atTheBreak) {
                announce(id, "HT", f);   // the half-time odds only if it's half-time now (not saved late in the 2nd half)
            }
        }
        if (finished && !have.containsKey("FT")) {
            putSnapshot(id, "FT", f.get("homeScore"), f.get("awayScore"), statsFromRows(Json.maps(match.get("fullTime"))));
        }
        String point = pointFor(f);
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

    /** The minute point a live game is at: 10 (10-24'), 25 (25-44'), 60 (60-79'), 80 (80'+); else null. */
    static String pointFor(Map<String, Object> f) {
        if (!Json.yes(f.get("live")) || isFinished(f)) {
            return null;
        }
        int e;
        try {
            e = Integer.parseInt(String.valueOf(f.get("elapsed")).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        return e < 10 ? null : e < 25 ? "10" : e < 45 ? "25" : e < 60 ? null : e < 80 ? "60" : "80";
    }

    /** Ticked games that are about to start or being played (and not finished with final stats). */
    private List<Map<String, Object>> active(Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : tracked) {
            Map<String, Object> f = fixtures.get(id);
            if (f == null) {
                continue;
            }
            Instant ko = instant(f.get("kickoff"));
            if (ko == null || now.isBefore(ko.minus(BEFORE)) || now.isAfter(ko.plus(AFTER))) {
                continue;
            }
            if (isOff(f)) {
                continue;
            }
            Map<String, Object> s = stats.get(id);
            if (isFinished(f) && s != null && Json.yes(s.get("final"))) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static boolean isFinished(Map<String, Object> f) {
        String st = String.valueOf(f.get("status")).toUpperCase();
        return st.startsWith("FINISH") || st.equals("FT") || st.equals("AET") || st.equals("AFTER_PENALTIES");
    }

    private static boolean isOff(Map<String, Object> f) {
        String st = String.valueOf(f.get("status")).toUpperCase();
        return st.contains("CANCEL") || st.contains("POSTPON") || st.contains("ABANDON") || st.contains("SUSPEND");
    }

    // ---------------------------------------------------------------- GOAL API data -> ours

    /** The fixtures in a GOAL API answer ({"data": [...]}). */
    private static List<Map<String, Object>> list(Map<String, Object> body) {
        return Json.maps(body.get("data"));
    }

    /** The parts of a GOAL API fixture we use. */
    static Map<String, Object> summary(Map<String, Object> f, String tournament) {
        Map<String, Object> s = new LinkedHashMap<>() {
            @Override
            public Object put(String k, Object v) {   // the JSON parser gives null as the text "null"
                return super.put(k, "null".equals(v) ? null : v);
            }
        };
        s.put("id", f.get("id"));
        s.put("tournament", tournament);
        s.put("leagueId", f.get("leagueId"));
        s.put("date", f.get("matchDate"));
        s.put("kickoff", f.get("kickoffUtc"));
        s.put("home", f.get("homeTeamName"));
        s.put("away", f.get("awayTeamName"));
        s.put("homeScore", f.get("homeTeamScore"));
        s.put("awayScore", f.get("awayTeamScore"));
        s.put("status", f.get("matchStatus"));
        s.put("live", "1".equals(String.valueOf(f.get("matchLive"))) || playing(f));
        s.put("minute", f.get("matchMinute"));      // "6", "HT", "Not Started"...
        s.put("elapsed", f.get("matchElapsed"));
        s.put("extra", f.get("matchExtra"));
        s.put("period", f.get("matchPeriod"));
        s.put("htHome", f.get("homeTeamHalftimeScore"));
        s.put("htAway", f.get("awayTeamHalftimeScore"));
        s.put("updatedAt", Instant.now().toString());
        stoppage(s, Instant.now());
        return s;
    }

    /** When each game's half first sat at 45' / 90' ("id:45"), for working out stoppage time GOAL API doesn't send. */
    private static final Map<String, Instant> AT_FULL_MINUTE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Stoppage time: GOAL API stays at 45 / 90 with no added minutes (seen at 94' of Doncaster v Huddersfield,
     * 26 Nov), so a half still at 45' or 90' gets "extra" = whole minutes since we first saw it there: 90+4'.
     * GOAL API's own added minutes win when it sends them.
     */
    static void stoppage(Map<String, Object> s, Instant now) {
        Object extra = s.get("extra");
        if (extra != null && !String.valueOf(extra).isBlank() && !"0".equals(String.valueOf(extra).trim())) {
            return;
        }
        String period = String.valueOf(s.get("period")).toUpperCase(java.util.Locale.ROOT);
        int e;
        try {
            e = Integer.parseInt(String.valueOf(s.get("elapsed")).trim());
        } catch (NumberFormatException x) {
            return;
        }
        boolean full = (e == 45 && period.contains("FIRST")) || (e == 90 && period.contains("SECOND"));
        if (!full || !Boolean.TRUE.equals(s.get("live"))) {
            return;
        }
        Instant first = AT_FULL_MINUTE.computeIfAbsent(s.get("id") + ":" + e, k -> now);
        long mins = Duration.between(first, now).toMinutes();
        if (mins >= 1) {
            s.put("extra", String.valueOf(mins));
        }
    }

    /**
     * Being played, going by its status and period: GOAL API's matchLive flag can still say 0 after kick-off
     * (seen at 1' of Doncaster v Huddersfield, 26 Nov), which stopped the stats being fetched.
     */
    static boolean playing(Map<String, Object> f) {
        String status = String.valueOf(f.get("matchStatus")).toUpperCase(java.util.Locale.ROOT);
        String period = String.valueOf(f.get("matchPeriod")).toUpperCase(java.util.Locale.ROOT);
        if (status.startsWith("FINISH") || status.startsWith("FT") || status.contains("CANCEL") || status.contains("POSTPON")
                || status.contains("ABANDON") || status.equals("SCHEDULED") || status.equals("NOT_STARTED")) {
            return false;
        }
        return status.contains("LIVE") || status.contains("PLAY") || status.contains("HALF")
                || period.contains("HALF") || period.contains("EXTRA") || period.contains("PENALT");
    }

    /** Team stats in a fixed order, xG estimate first, plus each side's players with shots. */
    static Map<String, Object> computeStats(Map<String, Object> body) {
        Map<String, Object> data = Json.map(body.get("data"));
        Map<String, Object> match = Json.map(data.get("match"));
        Map<String, Object> out = statsFromRows(Json.maps(match.get("fullTime")));
        Map<String, Object> players = Json.map(data.get("players"));
        out.put("homePlayers", shooters(players.get("home")));
        out.put("awayPlayers", shooters(players.get("away")));
        Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
        all.put("home", allShooters(players.get("home")));
        all.put("away", allShooters(players.get("away")));
        out.put("allPlayers", all);   // taken out again by refresh(): only for the timeline
        out.put("updatedAt", Instant.now().toString());
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
        list.addAll(events(before, f, prevStats, s, prevPlayers, players, list));
    }

    /** The new events between two refreshes (see addEvents); goals found earlier without a scorer get him filled in. */
    static List<Map<String, Object>> events(Map<String, Object> before, Map<String, Object> f, Map<String, Object> prevStats,
                                            Map<String, Object> s, Map<String, List<Map<String, Object>>> prevPlayers,
                                            Map<String, List<Map<String, Object>>> players, List<Map<String, Object>> sofar) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (before == null || f == null) {
            return out;   // nothing to compare with yet
        }
        String was = phase(before), now = phase(f);
        if ("pre".equals(was) && ("play".equals(now) || "ht".equals(now))) {
            out.add(event(f, "kickoff", null, null, null, 0));
        }
        if ("ht".equals(was) && "play".equals(now)) {
            out.add(event(f, "second", null, null, null, 0));
        }
        // goals, with scorers from the players' goal counts
        String score = Json.text(f.get("homeScore")) + "–" + Json.text(f.get("awayScore"));
        String[] sides = {"home", "away"};
        for (String side : sides) {
            int up = num(Json.text(f.get(side + "Score"))) - num(Json.text(before.get(side + "Score")));
            List<String> scorers = risers(prevPlayers, players, side, "goals");
            for (int i = 0; i < up; i++) {
                out.add(event(f, "goal", side, i < scorers.size() ? scorers.get(i) : null, score, 0));
            }
            if (up <= 0 && !scorers.isEmpty()) {   // GOAL API added the scorer a refresh after the goal
                for (String name : scorers) {
                    for (int j = sofar.size() - 1; j >= 0; j--) {
                        Map<String, Object> e = sofar.get(j);
                        if ("goal".equals(e.get("kind")) && side.equals(e.get("side")) && e.get("player") == null) {
                            e.put("player", name);
                            break;
                        }
                    }
                }
            }
        }
        // penalties, red and yellow cards: the team's counts
        if (prevStats != null) {
            String[][] kinds = {{"Penalties", "penalty"}, {"Red cards", "red"}, {"Yellow cards", "yellow"}};
            for (String[] k : kinds) {
                int[] a = count(prevStats, k[0]), b = count(s, k[0]);
                for (int i = 0; i < 2; i++) {
                    for (int n = a[i]; n < b[i]; n++) {
                        out.add(event(f, k[1], sides[i], null, null, 0));
                    }
                }
            }
        }
        // shots on target (a goal is one too, so those aren't listed twice)
        for (String side : sides) {
            Map<String, Integer> goals = new LinkedHashMap<>();
            for (String name : risers(prevPlayers, players, side, "goals")) {
                goals.merge(name, 1, Integer::sum);
            }
            for (String name : risers(prevPlayers, players, side, "onTarget")) {
                if (goals.merge(name, -1, Integer::sum) >= 0) {
                    continue;
                }
                out.add(event(f, "shot", side, name, null, 0));
            }
        }
        if (!"ht".equals(was) && "ht".equals(now)) {
            out.add(event(f, "ht", null, null, Json.text(f.get("homeScore")) + "–" + Json.text(f.get("awayScore")), 1));
        }
        if (!"ft".equals(was) && "ft".equals(now)) {
            out.add(event(f, "ft", null, null, score, 2));
        }
        return out;
    }

    /** pre (not started), play, ht, ft, or other (called off...). */
    static String phase(Map<String, Object> f) {
        if (isFinished(f)) {
            return "ft";
        }
        String period = String.valueOf(f.get("period")).toUpperCase(java.util.Locale.ROOT);
        if ("HT".equalsIgnoreCase(String.valueOf(f.get("minute"))) || period.contains("HALF_TIME")) {
            return "ht";
        }
        if (Json.yes(f.get("live"))) {
            return "play";
        }
        return isOff(f) ? "other" : "pre";
    }

    /** Names of a side's players whose count went up since last time, once per extra (two goals: twice). */
    private static List<String> risers(Map<String, List<Map<String, Object>>> prev, Map<String, List<Map<String, Object>>> now,
                                       String side, String key) {
        List<String> out = new ArrayList<>();
        if (prev == null || now == null) {
            return out;
        }
        Map<String, Integer> was = new LinkedHashMap<>();
        for (Map<String, Object> p : Json.maps(prev.get(side))) {
            was.put(String.valueOf(p.get("name")), num(Json.text(p.get(key))));
        }
        for (Map<String, Object> p : Json.maps(now.get(side))) {
            String name = String.valueOf(p.get("name"));
            for (int n = was.getOrDefault(name, 0); n < num(Json.text(p.get(key))); n++) {
                out.add(name);
            }
        }
        return out;
    }

    private static int[] count(Map<String, Object> st, String label) {
        for (Map<String, Object> r : Json.maps(st.get("rows"))) {
            if (label.equals(r.get("label"))) {
                return new int[] {num(Json.text(r.get("home"))), num(Json.text(r.get("away")))};
            }
        }
        return new int[] {0, 0};
    }

    /**
     * One timeline event: kind, side (home / away / null), player, score (goals, HT, FT), the minute label (23',
     * 45+2', HT, FT), half (1 / 2) and elapsed (minutes, for showing events up to a point) and order (sorting).
     * late: 1 for half time and 2 for full time, so they sort after anything in the same minute.
     */
    private static Map<String, Object> event(Map<String, Object> f, String kind, String side, String player, String score, int late) {
        Map<String, Object> e = new LinkedHashMap<>();
        int el = num(Json.text(f.get("elapsed"))), extra = num(Json.text(f.get("extra")));
        String period = String.valueOf(f.get("period")).toUpperCase(java.util.Locale.ROOT);
        String ph = phase(f);
        int half = "ft".equals(ph) || period.contains("SECOND") || (el > 45 && !period.contains("FIRST")) ? 2 : 1;
        if ("ht".equals(ph)) {
            el = 45;
            half = 1;
        }
        if ("ft".equals(ph) && el == 0) {
            el = 90;
        }
        String label = "kickoff".equals(kind) ? "0'" : "ht".equals(ph) ? "HT" : "ft".equals(ph) ? "FT"
                : el + (extra > 0 ? "+" + extra : "") + "'";
        e.put("kind", kind);
        e.put("side", side);
        e.put("player", player);
        e.put("score", score);
        e.put("minute", label);
        e.put("half", half);
        e.put("elapsed", "kickoff".equals(kind) ? 0 : el);
        e.put("order", "kickoff".equals(kind) ? 0 : half * 100000 + el * 100 + extra * 2 + late);
        e.put("at", Instant.now().toString());
        return e;
    }

    /** One period's GOAL API stat rows -> our rows (xG estimate first) and xG. */
    static Map<String, Object> statsFromRows(List<Map<String, Object>> apiRows) {
        Map<String, String[]> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : apiRows) {
            byType.putIfAbsent(String.valueOf(row.get("type")),
                    new String[] {Json.text(row.get("home")), Json.text(row.get("away"))});
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        double[] xg = xg(byType);
        if (xg != null) {
            rows.add(row("xG (est.)", String.format(java.util.Locale.ROOT, "%.2f", xg[0]), String.format(java.util.Locale.ROOT, "%.2f", xg[1])));
            out.put("xg", List.of(round2(xg[0]), round2(xg[1])));
        }
        String[][] order = {
            {"Shots Total", "Shots"}, {"Shots On Goal", "Shots on target"}, {"Shots Inside Box", "Shots inside box"},
            {"Dangerous Attacks", "Dangerous attacks"}, {"Corners", "Corners"}, {"Ball Possession", "Possession"},
            {"Attacks", "Attacks"}, {"Yellow Cards", "Yellow cards"}, {"Red Cards", "Red cards"},
            {"Penalty", "Penalties"}, {"Fouls", "Fouls"}, {"Offsides", "Offsides"}, {"Saves", "Saves"}};
        for (String[] o : order) {
            String[] v = byType.get(o[0]);
            if (v != null) {
                rows.add(row(o[1], v[0], v[1]));
            }
        }
        out.put("rows", rows);
        return out;
    }

    /** xG estimate per side from shot counts, or null if GOAL API has no shot split yet. */
    static double[] xg(Map<String, String[]> byType) {
        String[] in = byType.get("Shots Inside Box"), outside = byType.get("Shots Outside Box"), pen = byType.get("Penalty");
        if (in == null && outside == null) {
            return null;
        }
        double[] r = new double[2];
        for (int i = 0; i < 2; i++) {
            int pens = pen == null ? 0 : num(pen[i]);
            int inside = in == null ? 0 : num(in[i]);
            int out = outside == null ? 0 : num(outside[i]);
            r[i] = Math.max(0, inside - pens) * XG_INSIDE + out * XG_OUTSIDE + pens * XG_PENALTY;
        }
        return r;
    }

    /** Players who have had a shot, most shots on target first (top 5). */
    /** Every player with a shot: name, shots, onTarget, goals (unsorted, not cut to 5). */
    private static List<Map<String, Object>> allShooters(Object list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : Json.maps(list)) {
            int shots = num(Json.text(p.get("shotsTotal"))), goals = num(Json.text(p.get("goals")));
            if (shots > 0 || goals > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.get("playerName"));
                m.put("shots", shots);
                m.put("onTarget", num(Json.text(p.get("shotsOnGoal"))));
                m.put("goals", goals);
                out.add(m);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> shooters(Object list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : Json.maps(list)) {
            if (num(Json.text(p.get("shotsTotal"))) > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.get("playerName"));
                m.put("shots", num(Json.text(p.get("shotsTotal"))));
                m.put("onTarget", num(Json.text(p.get("shotsOnGoal"))));
                m.put("goals", num(Json.text(p.get("goals"))));
                out.add(m);
            }
        }
        out.sort(Comparator.<Map<String, Object>>comparingInt(m -> -(int) m.get("onTarget"))
                .thenComparingInt(m -> -(int) m.get("shots")));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static Map<String, Object> row(String label, String home, String away) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("home", home);
        m.put("away", away);
        return m;
    }

    private static int num(String s) {
        try {
            return s == null ? 0 : (int) Math.round(Double.parseDouble(s.replace("%", "").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round2(double d) {
        return Math.round(d * 100) / 100.0;
    }

    private static Instant instant(Object o) {
        try {
            return o == null ? null : Instant.parse(String.valueOf(o));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Forget games that kicked off more than a day ago (14 days for games with snapshots). */
    private void prune() {
        Instant now = Instant.now();
        fixtures.values().removeIf(f -> {
            Instant ko = instant(f.get("kickoff"));
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
                for (String table : new String[] {"live_events", "live_snapshots", "live_players", "live_stat_rows", "live_stats", "live_games"}) {
                    store.update("DELETE FROM " + table);
                }
                List<String> ticks = new ArrayList<>(tracked);
                for (Map<String, Object> f : fixtures.values()) {
                    String id = String.valueOf(f.get("id"));
                    int tick = ticks.indexOf(id);
                    store.update("INSERT OR REPLACE INTO live_games (id, tournament, league_id, date, kickoff, home, away, "
                            + "home_score, away_score, status, live, minute, elapsed, extra, period, updated_at, ticked) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            id, text(f, "tournament"), text(f, "leagueId"), text(f, "date"), text(f, "kickoff"),
                            text(f, "home"), text(f, "away"), text(f, "homeScore"), text(f, "awayScore"), text(f, "status"),
                            Json.yes(f.get("live")), text(f, "minute"), text(f, "elapsed"), text(f, "extra"),
                            text(f, "period"), text(f, "updatedAt"), tick < 0 ? null : tick + 1);
                }
                stats.forEach((id, st) -> {
                    List<?> xg = st.get("xg") instanceof List<?> l && l.size() == 2 ? l : null;
                    store.update("INSERT OR REPLACE INTO live_stats (game_id, xg_home, xg_away, minute, final, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", id, xg == null ? null : xg.get(0), xg == null ? null : xg.get(1),
                            text(st, "minute"), Json.yes(st.get("final")), text(st, "updatedAt"));
                    int position = 0;
                    for (Map<String, Object> r : Json.maps(st.get("rows"))) {
                        store.update("INSERT INTO live_stat_rows (game_id, position, label, home, away) VALUES (?, ?, ?, ?, ?)",
                                id, position++, text(r, "label"), text(r, "home"), text(r, "away"));
                    }
                    for (String side : new String[] {"home", "away"}) {
                        int n = 0;
                        for (Map<String, Object> pl : Json.maps(st.get(side + "Players"))) {
                            store.update("INSERT INTO live_players (game_id, side, position, name, shots, on_target, goals) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, side, n++, text(pl, "name"),
                                    pl.get("shots"), pl.get("onTarget"), pl.get("goals"));
                        }
                    }
                });
                snapshots.forEach((id, byPeriod) -> byPeriod.forEach((period, snap) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("xg", snap.get("xg"));
                    body.put("rows", snap.get("rows"));
                    store.update("INSERT INTO live_snapshots (game_id, period, home_score, away_score, stats, saved_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", id, period, text(snap, "homeScore"), text(snap, "awayScore"),
                            Json.write(body), text(snap, "savedAt"));
                }));
                timeline.forEach((id, list) -> {
                    int seq = 0;
                    for (Map<String, Object> e : list) {
                        store.update("INSERT INTO live_events (game_id, seq, kind, side, player, score, minute, half, elapsed, "
                                + "sort_order, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, seq++, text(e, "kind"),
                                text(e, "side"), text(e, "player"), text(e, "score"), text(e, "minute"), e.get("half"),
                                e.get("elapsed"), e.get("order"), text(e, "at"));
                    }
                });
                store.update("DELETE FROM live_follow");
                for (Map<String, Object> w : following) {
                    store.update("INSERT OR REPLACE INTO live_follow (tournament, kickoff, home, away) VALUES (?, ?, ?, ?)",
                            text(w, "tournament"), text(w, "kickoff"), text(w, "home"), text(w, "away"));
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

    private static String text(Map<String, Object> m, String key) {
        return Json.text(m.get(key));
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
            fixtures.put(String.valueOf(f.get("id")), f);
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
                    rs -> row(rs.getString(1), rs.getString(2), rs.getString(3)), id));
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
        for (Map<String, Object> e : store.query("SELECT * FROM live_events ORDER BY game_id, seq", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("game_id"));
            m.put("kind", rs.getString("kind"));
            m.put("side", rs.getString("side"));
            m.put("player", rs.getString("player"));
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
        following.addAll(store.query("SELECT * FROM live_follow ORDER BY kickoff", rs -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("tournament", rs.getString("tournament"));
            w.put("kickoff", rs.getString("kickoff"));
            w.put("home", rs.getString("home"));
            w.put("away", rs.getString("away"));
            return w;
        }));
        lastRefresh = store.refreshedAt("live").orElse(null);
        lastList = store.refreshedAt("live-list").orElse(null);
        store.usage("goal-api").ifPresent(api::restoreUsage);
        prune();
    }

}
