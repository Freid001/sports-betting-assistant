package com.sportsbettingassistant.predictions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.sportsbettingassistant.football.Competitions;
import com.sportsbettingassistant.football.Games;
import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * The prediction log: what the model said before a game (and at each bet window during it), kept to score against
 * the result later, so we can see how accurate it is and whether it beats the bookmaker.
 * <p>
 * The pages work the predictions out (the model runs in the browser) and send them here. Pre-match ones can be
 * updated until kick-off (the last before kick-off counts); a bet window's is kept as first sent, never changed.
 */
@Service
public class PredictionLog {

    static final Set<String> POINTS = Set.of("PRE", "10", "25", "30", "HT", "60", "80");   // 10, 25: older logs (the windows are 30', HT, 60', 80' now)

    private final Store store;
    private final Games games;

    public PredictionLog(Store store) {
        this.store = store;
        this.games = new Games(store);
    }

    /** The model's numbers a prediction carries, each a {name: number} map (prediction_numbers.kind). */
    static final List<String> NUMBERS = List.of("chances", "view", "ours", "bookmaker", "odds");

    /**
     * Saves one prediction: {matchId (The Odds API's event id), point, kickoff, competition, homeTeamId, awayTeamId,
     * version, confidence, minute, score:[h, a], chances, view, ours, bookmaker, odds, recommended, predictionRows}.
     * Returns false if it wasn't saved: a pre-match one after kick-off, a bet window already saved, or a game whose
     * teams aren't known.
     */
    public boolean save(Map<String, Object> p, Instant now) {
        String matchId = Json.text(p.get("matchId")), point = Json.text(p.get("point")), kickoff = Json.text(p.get("kickoff"));
        if (matchId == null || matchId.isBlank() || !POINTS.contains(point) || kickoff == null) {
            throw new IllegalArgumentException("A prediction needs matchId, point (PRE, 10, 25, HT, 60, 80) and kickoff.");
        }
        Instant ko;
        try {
            ko = Instant.parse(kickoff);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad kickoff: " + kickoff);
        }
        boolean pre = "PRE".equals(point);
        if (pre && !now.isBefore(ko)) {
            return false;   // too late: a pre-match prediction has to be made before kick-off
        }
        String competition = Json.text(p.get("competition"));
        Optional<Long> found = games.link(Games.ODDS, matchId, competition == null ? null : Competitions.CODES.get(competition),
                ko.toString(), id(p.get("homeTeamId")), id(p.get("awayTeamId")));
        if (found.isEmpty()) {
            return false;   // its teams aren't known: no game to keep it with
        }
        long game = found.get();
        if (!pre && store.first("SELECT 1 FROM predictions WHERE game_id = ? AND point = ?", rs -> 1, game, point).isPresent()) {
            return false;   // a bet window's snapshot is never changed
        }
        List<?> score = p.get("score") instanceof List<?> l && l.size() == 2 ? l : null;
        boolean bets = p.get("recommended") instanceof List<?>;
        store.transaction(() -> {
            store.update("INSERT OR REPLACE INTO predictions (game_id, point, saved_at, model_version, confidence, minute, "
                    + "home_goals, away_goals, has_bets) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", game, point, now.toString(),
                    Json.text(p.get("version")), Json.text(p.get("confidence")), id(p.get("minute")),
                    score == null ? null : id(score.get(0)), score == null ? null : id(score.get(1)), bets);
            store.update("DELETE FROM prediction_numbers WHERE game_id = ? AND point = ?", game, point);
            for (String kind : NUMBERS) {
                Json.map(p.get(kind)).forEach((name, v) -> {
                    if (v instanceof Number n) {
                        store.update("INSERT INTO prediction_numbers (game_id, point, kind, name, value) VALUES (?, ?, ?, ?, ?)",
                                game, point, kind, name, n.doubleValue());
                    }
                });
            }
            store.update("DELETE FROM prediction_bets WHERE game_id = ? AND point = ?", game, point);
            int rank = 0;
            for (Map<String, Object> b : Json.maps(p.get("recommended"))) {
                Map<String, Object> r = Json.map(b.get("r")), verdict = Json.map(r.get("verdict"));
                Object price = b.get("price") != null ? b.get("price") : Json.map(b.get("o")).get("price");
                store.update("INSERT INTO prediction_bets (game_id, point, rank, market, label, price, chance, fits, hint, ours, "
                        + "fair, ret, verdict, verdict_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        game, point, rank++, Json.text(b.get("key")),
                        Json.text(b.get("name") != null ? b.get("name") : b.get("label")), number(price), number(b.get("chance")),
                        b.get("fits") instanceof Boolean f ? f : null, Json.text(b.get("hint")), number(r.get("ours")),
                        number(r.get("fair")), number(r.get("ret")), Json.text(verdict.get("text")), Json.text(verdict.get("cls")));
            }
            // the Bets page logs PRE without the Prediction card's lines: then the ones the match page saved are kept
            if (p.get("predictionRows") instanceof List<?> lines) {
                store.update("DELETE FROM prediction_lines WHERE game_id = ? AND point = ?", game, point);
                int n = 0;
                for (Object line : lines) {
                    List<?> l = line instanceof List<?> x ? x : List.of();
                    store.update("INSERT INTO prediction_lines (game_id, point, line_no, label, text) VALUES (?, ?, ?, ?, ?)",
                            game, point, n++, l.size() > 0 ? Json.text(l.get(0)) : null, l.size() > 1 ? Json.text(l.get(1)) : null);
                }
            }
        });
        return true;
    }

    /**
     * Every prediction with its game's result once it's finished (from Refresh Stats, or the final score of a game
     * followed live), in the shape the pages sent it: [{matchId, point, kickoff, competition, home, away (as The Odds
     * API names them), homeTeamId, awayTeamId, version, confidence, minute, score, chances, view, ours, bookmaker, odds,
     * recommended, predictionRows, savedAt, result: {home, away, htHome, htAway} or null}], newest first.
     */
    public List<Map<String, Object>> all() {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        store.query("SELECT p.*, g.kickoff_at, g.competition, g.home_team_id, g.away_team_id, g.status, g.home_goals AS ft_home, "
                + "g.away_goals AS ft_away, g.ht_home, g.ht_away, "
                + "(SELECT source_id FROM game_sources s WHERE s.game_id = g.id AND s.source = 'odds' LIMIT 1) AS match_id, "
                + "COALESCE((SELECT name FROM team_names n WHERE n.source = 'odds' AND n.team_id = g.home_team_id LIMIT 1), h.name) AS home, "
                + "COALESCE((SELECT name FROM team_names n WHERE n.source = 'odds' AND n.team_id = g.away_team_id LIMIT 1), a.name) AS away "
                + "FROM predictions p JOIN games g ON g.id = p.game_id LEFT JOIN teams h ON h.id = g.home_team_id "
                + "LEFT JOIN teams a ON a.id = g.away_team_id ORDER BY g.kickoff_at DESC, p.game_id, p.point", rs -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("matchId", rs.getString("match_id"));
                    out.put("point", rs.getString("point"));
                    out.put("kickoff", rs.getString("kickoff_at"));
                    out.put("competition", Competitions.tournament(rs.getString("competition")));
                    out.put("home", rs.getString("home"));
                    out.put("away", rs.getString("away"));
                    out.put("homeTeamId", Store.longOrNull(rs, "home_team_id"));
                    out.put("awayTeamId", Store.longOrNull(rs, "away_team_id"));
                    out.put("version", rs.getString("model_version"));
                    out.put("confidence", rs.getString("confidence"));
                    Long minute = Store.longOrNull(rs, "minute"), h = Store.longOrNull(rs, "home_goals"), a = Store.longOrNull(rs, "away_goals");
                    if (minute != null) {
                        out.put("minute", minute);
                    }
                    if (h != null && a != null) {
                        out.put("score", List.of(h, a));
                    }
                    if (rs.getInt("has_bets") == 1) {
                        out.put("recommended", new ArrayList<Map<String, Object>>());
                    }
                    out.put("savedAt", rs.getString("saved_at"));
                    Long fh = Store.longOrNull(rs, "ft_home"), fa = Store.longOrNull(rs, "ft_away");
                    if ("FINISHED".equals(rs.getString("status")) && fh != null && fa != null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("home", fh);
                        r.put("away", fa);
                        r.put("htHome", Store.longOrNull(rs, "ht_home"));
                        r.put("htAway", Store.longOrNull(rs, "ht_away"));
                        out.put("result", r);
                    } else {
                        out.put("result", null);
                    }
                    byKey.put(rs.getLong("game_id") + "|" + rs.getString("point"), out);
                    return null;
                });
        store.query("SELECT * FROM prediction_numbers ORDER BY game_id, point, kind, name", rs -> {
            Map<String, Object> out = byKey.get(rs.getLong("game_id") + "|" + rs.getString("point"));
            if (out != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> kind = (Map<String, Object>) out.computeIfAbsent(rs.getString("kind"), k -> new LinkedHashMap<>());
                kind.put(rs.getString("name"), rs.getDouble("value"));
            }
            return null;
        });
        store.query("SELECT * FROM prediction_bets ORDER BY game_id, point, rank", rs -> {
            Map<String, Object> out = byKey.get(rs.getLong("game_id") + "|" + rs.getString("point"));
            if (out == null) {
                return null;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            if (rs.getString("market") != null) {   // a pre-match bet
                b.put("key", rs.getString("market"));
                b.put("name", rs.getString("label"));
                b.put("price", Store.doubleOrNull(rs, "price"));
                b.put("chance", Store.doubleOrNull(rs, "chance"));
            } else {                                  // a bet window's row, as the in-play table drew it
                b.put("label", rs.getString("label"));
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("price", Store.doubleOrNull(rs, "price"));
                b.put("o", o);
                b.put("chance", Store.doubleOrNull(rs, "chance"));
                Long fits = Store.longOrNull(rs, "fits");
                b.put("fits", fits != null && fits == 1);
                b.put("hint", rs.getString("hint"));
                if (rs.getString("verdict") != null || Store.doubleOrNull(rs, "ret") != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ours", Store.doubleOrNull(rs, "ours"));
                    r.put("fair", Store.doubleOrNull(rs, "fair"));
                    r.put("ret", Store.doubleOrNull(rs, "ret"));
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("text", rs.getString("verdict"));
                    v.put("cls", rs.getString("verdict_class"));
                    r.put("verdict", v);
                    b.put("r", r);
                } else {
                    b.put("r", null);
                }
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) out.computeIfAbsent("recommended", k -> new ArrayList<>());
            list.add(b);
            return null;
        });
        store.query("SELECT * FROM prediction_lines ORDER BY game_id, point, line_no", rs -> {
            Map<String, Object> out = byKey.get(rs.getLong("game_id") + "|" + rs.getString("point"));
            if (out != null) {
                @SuppressWarnings("unchecked")
                List<List<String>> lines = (List<List<String>>) out.computeIfAbsent("predictionRows", k -> new ArrayList<>());
                lines.add(java.util.Arrays.asList(rs.getString("label"), rs.getString("text")));
            }
            return null;
        });
        return new ArrayList<>(byKey.values());
    }

    /** Predictions older than this are dropped (keeps the database small). */
    static final Duration KEEP = Duration.ofDays(400);

    public void prune(Instant now) {
        String old = "SELECT p.game_id FROM predictions p JOIN games g ON g.id = p.game_id WHERE g.kickoff_at < ?";
        String before = now.minus(KEEP).toString();
        store.transaction(() -> {
            for (String table : new String[] {"prediction_numbers", "prediction_lines", "prediction_bets", "predictions"}) {
                store.update("DELETE FROM " + table + " WHERE game_id IN (" + old + ")", before);
            }
        });
    }

    private static Double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? null : Double.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long id(Object o) {
        long n = Json.number(o);
        return n < 0 ? null : n;
    }
}
