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
import org.springframework.beans.factory.annotation.Value;
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

    private final GoalApiClient api;
    private final Store store;
    private final Map<String, String> leagues;   // our tournament key -> GOAL API league id
    private final int refreshMinutes, maxGames, reserve;

    private final Map<String, Map<String, Object>> fixtures = new LinkedHashMap<>();  // id -> game summary
    private final Set<String> tracked = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> stats = new LinkedHashMap<>();     // id -> latest stats
    private Instant lastRefresh, lastList;
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
        out.put("refreshMinutes", refreshMinutes);
        out.put("maxGames", maxGames);
        out.put("reserveCalls", reserve);
        out.put("usage", api.usage());
        out.put("lastRefresh", Json.text(lastRefresh));
        out.put("listedAt", Json.text(lastList));
        out.put("watching", !active(Instant.now()).isEmpty());
        out.put("warning", warning);
        out.put("tracked", new ArrayList<>(tracked));
        List<Map<String, Object>> list = new ArrayList<>(fixtures.values());
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
        out.put("lastRefresh", Json.text(lastRefresh));
        out.put("refreshMinutes", refreshMinutes);
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

    // ---------------------------------------------------------------- the refresh

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public synchronized void tick() {
        Instant now = Instant.now();
        List<Map<String, Object>> on = active(now);
        if (on.isEmpty()) {
            return;
        }
        if (lastRefresh != null && lastRefresh.plus(Duration.ofMinutes(refreshMinutes)).isAfter(now)) {
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
                Map<String, Object> s = computeStats(api.getJson("/fixtures/" + id + "/statistics", Map.of()));
                s.put("minute", f.get("minute"));
                s.put("final", isFinished(f));
                stats.put(id, s);
            } catch (RuntimeException e) {
                warning = "Last refresh had errors: " + e.getMessage();
                log.warn("GOAL API stats for {} failed: {}", id, e.getMessage());
            }
        }
        lastRefresh = now;
        save();
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
        s.put("live", "1".equals(String.valueOf(f.get("matchLive"))));
        s.put("minute", f.get("matchMinute"));      // "6", "HT", "Not Started"...
        s.put("elapsed", f.get("matchElapsed"));
        s.put("extra", f.get("matchExtra"));
        s.put("period", f.get("matchPeriod"));
        s.put("updatedAt", Instant.now().toString());
        return s;
    }

    /** Team stats in a fixed order, xG estimate first, plus each side's players with shots. */
    static Map<String, Object> computeStats(Map<String, Object> body) {
        Map<String, Object> data = Json.map(body.get("data"));
        Map<String, Object> match = Json.map(data.get("match"));
        Map<String, String[]> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : Json.maps(match.get("fullTime"))) {
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
            {"Fouls", "Fouls"}, {"Offsides", "Offsides"}, {"Saves", "Saves"}};
        for (String[] o : order) {
            String[] v = byType.get(o[0]);
            if (v != null) {
                rows.add(row(o[1], v[0], v[1]));
            }
        }
        out.put("rows", rows);
        Map<String, Object> players = Json.map(data.get("players"));
        out.put("homePlayers", shooters(players.get("home")));
        out.put("awayPlayers", shooters(players.get("away")));
        out.put("updatedAt", Instant.now().toString());
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

    /** Forget games that kicked off more than a day ago. */
    private void prune() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        fixtures.values().removeIf(f -> {
            Instant ko = instant(f.get("kickoff"));
            return ko != null && ko.isBefore(cutoff);
        });
        tracked.retainAll(fixtures.keySet());
        stats.keySet().retainAll(fixtures.keySet());
    }

    // ---------------------------------------------------------------- saved copy

    /** Writes the games, ticks, latest stats and calls left to the live tables (small: rewritten each time). */
    private void save() {
        try {
            store.transaction(() -> {
                for (String table : new String[] {"live_players", "live_stat_rows", "live_stats", "live_games"}) {
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
        lastRefresh = store.refreshedAt("live").orElse(null);
        lastList = store.refreshedAt("live-list").orElse(null);
        store.usage("goal-api").ifPresent(api::restoreUsage);
        prune();
    }

}
