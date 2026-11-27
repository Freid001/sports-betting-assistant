package com.sportsbettingassistant.predictions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

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
        return store.query("SELECT p.*, m.home_goals, m.away_goals, m.ht_home, m.ht_away FROM predictions p "
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
