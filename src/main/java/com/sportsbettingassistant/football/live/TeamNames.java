package com.sportsbettingassistant.football.live;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Matching team names between sources ("Nottingham" / "Nottingham Forest", "Man Utd" / "Manchester United"):
 * a similarity score from their words, used to pair GOAL API's games with The Odds API's events.
 */
public final class TeamNames {

    private TeamNames() {
    }

    static boolean sameGame(Map<String, Object> w, String tournament, String kickoff, String home, String away) {
        return tournament.equals(w.get("tournament")) && near(w.get("kickoff"), kickoff)
                && similar(w.get("home"), home) + similar(w.get("away"), away) > 3;
    }

    /** Within 30 minutes of each other (ISO times). */
    static boolean near(Object a, String b) {
        Instant x = GoalData.instant(a), y = GoalData.instant(b);
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
}
