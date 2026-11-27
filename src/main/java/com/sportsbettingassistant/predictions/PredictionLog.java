package com.sportsbettingassistant.predictions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.sportsbettingassistant.football.live.TeamNames;
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

    public PredictionLog(Store store) {
        this.store = store;
    }

    /**
     * Saves one prediction: {matchId, point, kickoff, competition, home, away, homeTeamId, awayTeamId, version, ...}
     * (everything else is kept as sent). Returns false if it wasn't saved: a pre-match one after kick-off, or a bet
     * window already saved.
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
        if (!pre && store.first("SELECT 1 FROM predictions WHERE match_id = ? AND point = ?", rs -> 1, matchId, point).isPresent()) {
            return false;   // a bet window's snapshot is never changed
        }
        if (pre && p.get("predictionRows") == null) {
            // the Bets page logs it without the Prediction card's lines: keep the ones the match page saved
            store.first("SELECT body FROM predictions WHERE match_id = ? AND point = 'PRE'", rs -> rs.getString(1), matchId)
                    .map(b -> Json.parse(b).get("predictionRows")).ifPresent(rows -> p.put("predictionRows", rows));
        }
        store.update("INSERT OR REPLACE INTO predictions (match_id, point, kickoff, competition, home, away, home_team_id, "
                + "away_team_id, saved_at, version, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId, point, ko.toString(), Json.text(p.get("competition")), Json.text(p.get("home")), Json.text(p.get("away")),
                id(p.get("homeTeamId")), id(p.get("awayTeamId")), now.toString(), Json.text(p.get("version")), Json.write(p));
        return true;
    }

    /**
     * Every prediction with its game's result once it's finished (from the saved results: Refresh Stats or Live
     * Stats): [{...the prediction, savedAt, result: {home, away, htHome, htAway} or null}], newest first.
     */
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> all = store.query("SELECT p.*, m.home_goals, m.away_goals, m.ht_home, m.ht_away FROM predictions p "
                + "LEFT JOIN matches m ON m.status = 'FINISHED' AND m.home_team_id = p.home_team_id "
                + "AND m.away_team_id = p.away_team_id AND ABS(julianday(m.utc_date) - julianday(p.kickoff)) < 1 "
                + "ORDER BY p.kickoff DESC, p.match_id, p.point", rs -> {
                    Map<String, Object> out = new LinkedHashMap<>(Json.parse(rs.getString("body")));
                    out.put("savedAt", rs.getString("saved_at"));
                    Long hg = Store.longOrNull(rs, "home_goals"), ag = Store.longOrNull(rs, "away_goals");
                    if (hg != null && ag != null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("home", hg);
                        r.put("away", ag);
                        r.put("htHome", Store.longOrNull(rs, "ht_home"));
                        r.put("htAway", Store.longOrNull(rs, "ht_away"));
                        out.put("result", r);
                    } else {
                        out.put("result", null);
                    }
                    return out;
                });
        // not in the saved results yet (they come with Refresh Stats): a game followed live has its final score already
        List<Map<String, Object>> live = null;
        for (Map<String, Object> p : all) {
            if (p.get("result") == null) {
                if (live == null) {
                    live = finishedLiveGames();
                }
                p.put("result", liveResult(live, p));
            }
        }
        return all;
    }

    /** Followed games that have finished: {kickoff, home, away, homeGoals, awayGoals, htHome, htAway} (HT if saved). */
    private List<Map<String, Object>> finishedLiveGames() {
        return store.query("SELECT g.kickoff, g.home, g.away, g.home_score, g.away_score, h.home_score AS ht_home, "
                + "h.away_score AS ht_away FROM live_games g LEFT JOIN live_snapshots h ON h.game_id = g.id AND h.period = 'HT' "
                + "WHERE g.status LIKE 'FINISH%' OR g.status IN ('FT', 'AET')", rs -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("kickoff", rs.getString("kickoff"));
                    g.put("home", rs.getString("home"));
                    g.put("away", rs.getString("away"));
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("home", score(rs.getString("home_score")));
                    r.put("away", score(rs.getString("away_score")));
                    r.put("htHome", rs.getString("ht_home") == null ? null : score(rs.getString("ht_home")));
                    r.put("htAway", rs.getString("ht_away") == null ? null : score(rs.getString("ht_away")));
                    g.put("result", r);
                    return g;
                });
    }

    /** The finished live game that is this prediction's (kick-off within 30 minutes, team names alike), or null. */
    static Map<String, Object> liveResult(List<Map<String, Object>> games, Map<String, Object> p) {
        Instant ko = instant(Json.text(p.get("kickoff")));
        if (ko == null) {
            return null;
        }
        for (Map<String, Object> g : games) {
            Instant gk = instant(Json.text(g.get("kickoff")));
            if (gk != null && Duration.between(ko, gk).abs().toMinutes() <= 30
                    && TeamNames.similar(g.get("home"), p.get("home")) + TeamNames.similar(g.get("away"), p.get("away")) > 3) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) g.get("result");
                return r.get("home") == null || r.get("away") == null ? null : r;
            }
        }
        return null;
    }

    private static Long score(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(String iso) {
        try {
            return iso == null ? null : Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Predictions older than this are dropped (keeps the database small). */
    static final Duration KEEP = Duration.ofDays(400);

    public void prune(Instant now) {
        store.update("DELETE FROM predictions WHERE kickoff < ?", now.minus(KEEP).toString());
    }

    private static Long id(Object o) {
        long n = Json.number(o);
        return n < 0 ? null : n;
    }
}
