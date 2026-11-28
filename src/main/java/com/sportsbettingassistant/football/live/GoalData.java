package com.sportsbettingassistant.football.live;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sportsbettingassistant.json.Json;

/**
 * Reading GOAL API's answers for a live game: a fixture's summary (score, minute, status, stoppage time), its match
 * stats (our rows, the xG estimate, who had shots) and small helpers for its loosely typed numbers and text.
 * Pure functions: no calls, no state (LiveService does the calls and keeps the state).
 */
final class GoalData {

    private GoalData() {
    }

    static final double XG_INSIDE = 0.12, XG_OUTSIDE = 0.035, XG_PENALTY = 0.76;
    /** When each game's half first sat at 45' / 90' ("id:45"), for working out stoppage time GOAL API doesn't send. */
    private static final Map<String, Instant> AT_FULL_MINUTE = new java.util.concurrent.ConcurrentHashMap<>();

    static double round2(double d) {
        return Math.round(d * 100) / 100.0;
    }

    static int rowCount(Map<String, Object> st) {
        return st == null || !(st.get("rows") instanceof List<?> rows) ? 0 : rows.size();
    }

    static boolean isFinished(Map<String, Object> f) {
        String st = String.valueOf(f.get("status")).toUpperCase();
        return st.startsWith("FINISH") || st.equals("FT") || st.equals("AET") || st.equals("AFTER_PENALTIES");
    }

    static boolean isOff(Map<String, Object> f) {
        String st = String.valueOf(f.get("status")).toUpperCase();
        return st.contains("CANCEL") || st.contains("POSTPON") || st.contains("ABANDON") || st.contains("SUSPEND");
    }

    /** The fixtures in a GOAL API answer ({"data": [...]}). */
    static List<Map<String, Object>> list(Map<String, Object> body) {
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
        s.put("homeTeamGoalId", f.get("homeTeamId"));   // GOAL API's team ids: the teams, exactly (see Games)
        s.put("awayTeamGoalId", f.get("awayTeamId"));
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
    static List<Map<String, Object>> allShooters(Object list) {
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

    static List<Map<String, Object>> shooters(Object list) {
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

    static Map<String, Object> row(String label, String home, String away) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("home", home);
        m.put("away", away);
        return m;
    }

    static int num(String s) {
        try {
            return s == null ? 0 : (int) Math.round(Double.parseDouble(s.replace("%", "").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Instant instant(Object o) {
        try {
            return o == null ? null : Instant.parse(String.valueOf(o));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String text(Map<String, Object> m, String key) {
        return Json.text(m.get(key));
    }
}
