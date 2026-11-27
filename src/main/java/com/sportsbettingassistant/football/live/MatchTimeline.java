package com.sportsbettingassistant.football.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sportsbettingassistant.json.Json;

/**
 * The Timeline's events for a game: worked out refresh by refresh from the score, the team counts and the players'
 * shots (events), and GOAL API's own feeds (goals, cards, substitutions, commentary, the line-ups' keepers).
 * Each event: {kind, side, player, assist, info, score, minute, half, elapsed, order, source, at}.
 * Pure functions: no calls, no state.
 */
final class MatchTimeline {

    private MatchTimeline() {
    }

    /** The minute point a live game is at: 30 (30-44'), 60 (60-79'), 80 (80'+); else null (no 10': little has changed). */
    static String pointFor(Map<String, Object> f) {
        if (!Json.yes(f.get("live")) || GoalData.isFinished(f)) {
            return null;
        }
        int e;
        try {
            e = Integer.parseInt(String.valueOf(f.get("elapsed")).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        return e < 30 ? null : e < 45 ? "30" : e < 60 ? null : e < 80 ? "60" : "80";
    }

    /** GOAL API's substitutions: [{time "46", substitution "Off | On", team "home" / "away"}]: SUB, the player on, "for" the one off. */
    static List<Map<String, Object>> officialSubs(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> x : Json.maps(body.get("data"))) {
            String[] who = String.valueOf(x.get("substitution")).split("\\|");
            String side = "home".equalsIgnoreCase(Json.text(x.get("team"))) ? "home" : "away";
            if (who.length < 2) {
                continue;
            }
            Map<String, Object> e = official("sub", side, x, who[1].trim());
            e.put("info", "for " + who[0].trim());
            out.add(e);
        }
        return out;
    }

    /** The starting goalkeepers in GOAL API's line-ups: [home, away] (null if not listed). */
    static String[] keepersOf(Map<String, Object> body) {
        String[] out = new String[2];
        Map<String, Object> data = Json.map(body.get("data"));
        String[] sides = {"home", "away"};
        for (int i = 0; i < 2; i++) {
            for (Map<String, Object> p : Json.maps(Json.map(data.get(sides[i])).get("startingLineups"))) {
                if (String.valueOf(p.get("playerPosition")).toLowerCase(java.util.Locale.ROOT).startsWith("goalkeeper")) {
                    out[i] = Json.text(p.get("lineupPlayer"));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * GOAL API's live commentary: [{time "53:09", text "Nottingham shot on goal", state "J McAtee,"}]. Only some moments
     * are in it (not every shot), but each names the player: every kind with a player except the ones the official
     * feeds have in full (goals, cards, substitutions). Longer names first ("dangerous attack" before "attack").
     */
    static List<Map<String, Object>> commentary(Map<String, Object> body, String home, String away) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] kinds = {{"shot on goal", "shot"}, {"free kick", "freekick"}, {"dangerous attack", "danger"},
            {"attack", "attack"}, {"in possession", "possession"}, {"goal kick", "goalkick"}, {"penalty", "penalty"}};
        for (Map<String, Object> x : Json.maps(body.get("data"))) {
            String text = String.valueOf(x.get("text")), player = Json.text(x.get("state"));
            player = player == null ? "" : player.replaceAll("[,\\s]+$", "").trim();
            if (player.isEmpty()) {
                continue;
            }
            String side = home != null && text.startsWith(home) ? "home" : away != null && text.startsWith(away) ? "away" : null;
            if (side == null) {
                continue;
            }
            for (String[] k : kinds) {
                if (text.toLowerCase(java.util.Locale.ROOT).endsWith(" " + k[0])) {
                    int mm = GoalData.num(String.valueOf(x.get("time")).split(":")[0]);
                    Map<String, Object> t = new LinkedHashMap<>();
                    // the minute it's in (53:09 is the 54th); stoppage time as 90+n'
                    t.put("time", mm + 1 > 90 ? "90+" + (mm + 1 - 90) : String.valueOf(mm + 1));
                    t.put("scoreInfoTime", mm >= 45 ? "2nd Half" : "1st Half");
                    Map<String, Object> e = official(k[1], side, t, player);
                    e.put("info", "commentary");
                    // the exact time it gives (53:09): for the Timeline's order within a minute, and to show
                    String[] clock = String.valueOf(x.get("time")).split(":");
                    if (clock.length == 2) {
                        e.put("clock", String.valueOf(x.get("time")));
                        e.put("sec", GoalData.num(clock[1]));
                    }
                    out.add(e);
                    break;   // one kind per line ("dangerous attack" isn't an "attack" too)
                }
            }
        }
        return out;
    }

    /** GOAL API's goals: [{time "34", timeNum, homeScorer / awayScorer, ...Assist, score "1 - 2", info "Penalty", scoreInfoTime}]. */
    static List<Map<String, Object>> officialGoals(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : Json.maps(body.get("data"))) {
            if (!"GOAL".equalsIgnoreCase(String.valueOf(g.get("type")))) {
                continue;
            }
            boolean home = g.get("homeScorer") != null && !"null".equals(String.valueOf(g.get("homeScorer")));
            String side = home ? "home" : "away";
            Map<String, Object> e = official("goal", side, g, Json.text(g.get(side + "Scorer")));
            e.put("assist", Json.text(g.get(side + "Assist")));
            e.put("info", Json.text(g.get("info")));
            String score = Json.text(g.get("score"));
            e.put("score", score == null ? null : score.replace(" - ", "–"));
            out.add(e);
        }
        return out;
    }

    /** GOAL API's cards: [{time, timeNum, card "yellow card" / "red card", homeFault / awayFault (the player), scoreInfoTime}]. */
    static List<Map<String, Object>> officialCards(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : Json.maps(body.get("data"))) {
            String card = String.valueOf(c.get("card")).toLowerCase(java.util.Locale.ROOT);
            boolean home = c.get("homeFault") != null && !"null".equals(String.valueOf(c.get("homeFault")));
            String side = home ? "home" : "away";
            Map<String, Object> e = official(card.contains("red") ? "red" : "yellow", side, c, Json.text(c.get(side + "Fault")));
            if (card.contains("yellow") && card.contains("red")) {
                e.put("info", "Second yellow");
            }
            out.add(e);
        }
        return out;
    }

    static Map<String, Object> official(String kind, String side, Map<String, Object> x, String player) {
        String time = Json.text(x.get("time"));
        time = time == null ? "" : time.replace("'", "").trim();
        int el = GoalData.num(Json.text(x.get("timeNum")));
        if (el == 0) {
            el = GoalData.num(time.replaceAll("\\+.*", ""));
        }
        int extra = time.contains("+") ? GoalData.num(time.substring(time.indexOf('+') + 1)) : 0;
        String when = String.valueOf(x.get("scoreInfoTime")).toLowerCase(java.util.Locale.ROOT);
        int half = when.startsWith("1st") ? 1 : when.startsWith("2nd") ? 2 : el > 45 ? 2 : 1;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("side", side);
        e.put("player", player);
        e.put("assist", null);
        e.put("info", null);
        e.put("score", null);
        e.put("minute", (time.isEmpty() ? String.valueOf(el) : time) + "'");
        e.put("half", half);
        e.put("elapsed", el);
        e.put("order", half * 100000 + el * 100 + extra * 2);
        e.put("source", "api");
        e.put("at", Instant.now().toString());
        return e;
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
            int up = GoalData.num(Json.text(f.get(side + "Score"))) - GoalData.num(Json.text(before.get(side + "Score")));
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
        // saves (the side whose keeper made it: his name is added from the line-ups, see addEvents), corners, offsides:
        // the team's counts (GOAL API has no feed with each one)
        String[][] counted = {{"Saves", "save"}, {"Corners", "corner"}, {"Offsides", "offside"}};
        for (String[] k : counted) {
            if (!hasRow(prevStats, k[0])) {
                continue;
            }
            int[] a = count(prevStats, k[0]), b = count(s, k[0]);
            for (int i = 0; i < 2; i++) {
                for (int n = a[i]; n < b[i]; n++) {
                    out.add(event(f, k[1], sides[i], null, null, 0));
                }
            }
        }
        // shots on target (a goal is one too, so those aren't listed twice): only with the player's name (see risers)
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
        if (GoalData.isFinished(f)) {
            return "ft";
        }
        String period = String.valueOf(f.get("period")).toUpperCase(java.util.Locale.ROOT);
        if ("HT".equalsIgnoreCase(String.valueOf(f.get("minute"))) || period.contains("HALF_TIME")) {
            return "ht";
        }
        if (Json.yes(f.get("live"))) {
            return "play";
        }
        return GoalData.isOff(f) ? "other" : "pre";
    }

    /** Names of a side's players whose count went up since last time, once per extra (two goals: twice). */
    static List<String> risers(Map<String, List<Map<String, Object>>> prev, Map<String, List<Map<String, Object>>> now,
                                       String side, String key) {
        List<String> out = new ArrayList<>();
        if (prev == null || now == null) {
            return out;
        }
        Map<String, Integer> was = new LinkedHashMap<>();
        for (Map<String, Object> p : Json.maps(prev.get(side))) {
            was.put(String.valueOf(p.get("name")), GoalData.num(Json.text(p.get(key))));
        }
        for (Map<String, Object> p : Json.maps(now.get(side))) {
            String name = String.valueOf(p.get("name"));
            for (int n = was.getOrDefault(name, 0); n < GoalData.num(Json.text(p.get(key))); n++) {
                out.add(name);
            }
        }
        return out;
    }

    static boolean hasRow(Map<String, Object> st, String label) {
        return st != null && Json.maps(st.get("rows")).stream().anyMatch(r -> label.equals(r.get("label")));
    }

    static int[] count(Map<String, Object> st, String label) {
        for (Map<String, Object> r : Json.maps(st.get("rows"))) {
            if (label.equals(r.get("label"))) {
                return new int[] {GoalData.num(Json.text(r.get("home"))), GoalData.num(Json.text(r.get("away")))};
            }
        }
        return new int[] {0, 0};
    }

    /**
     * One timeline event: kind, side (home / away / null), player, score (goals, HT, FT), the minute label (23',
     * 45+2', HT, FT), half (1 / 2) and elapsed (minutes, for showing events up to a point) and order (sorting).
     * late: 1 for half time and 2 for full time, so they sort after anything in the same minute.
     */
    static Map<String, Object> event(Map<String, Object> f, String kind, String side, String player, String score, int late) {
        Map<String, Object> e = new LinkedHashMap<>();
        int el = GoalData.num(Json.text(f.get("elapsed"))), extra = GoalData.num(Json.text(f.get("extra")));
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
        // half time after all the first half's stoppage time, full time after everything
        e.put("order", "kickoff".equals(kind) ? 0 : "ht".equals(kind) ? 199998 : "ft".equals(kind) ? 1_000_000_000
                : half * 100000 + el * 100 + extra * 2 + late);
        e.put("at", Instant.now().toString());
        return e;
    }
}
