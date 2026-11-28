package com.sportsbettingassistant.football;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * One identity per player. GOAL API numbers a player one way in a team's squad list ('goal': its apiId) and another
 * in its top-scorer lists ('goal-scorer': its playerKey): each is a row in player_sources pointing at one players row.
 * A new id is matched to a player once, by name within his team; by id from then on.
 * <p>
 * His team's details (shirt, captain, injured, the team GOAL API files him under) are in team_players, his season
 * stats one row per stat in player_season_stats.
 */
public class Players {

    /** player_sources.source: GOAL API's squad id (apiId), and its top-scorer id (playerKey). */
    public static final String GOAL = "goal", GOAL_SCORER = "goal-scorer";

    /** GOAL API's player fields that aren't season numbers: kept as columns, not stats. */
    static final Set<String> NOT_STATS = Set.of("number", "age", "isCaptain", "injured", "filedUnder", "image");

    private final Store store;

    public Players(Store store) {
        this.store = store;
    }

    /** The player an API's id is for, if it's been linked. */
    public Optional<Long> player(String source, String sourceId) {
        return store.first("SELECT player_id FROM player_sources WHERE source = ? AND source_id = ?", rs -> rs.getLong(1),
                source, sourceId);
    }

    /**
     * The player an API's id is for: the one it's linked to, else his team's player with the same name that this
     * source hasn't got another id for (in the team's squad or its scorers), else a new player. Then linked.
     */
    public long link(String source, String sourceId, Long teamId, String name) {
        synchronized (store) {
            Optional<Long> linked = player(source, sourceId);
            if (linked.isPresent()) {
                return linked.get();
            }
            long[] id = {0};
            store.transaction(() -> {
                String want = FootballTables.plainName(name);
                List<Object[]> team = teamId == null ? List.of() : store.query("SELECT p.id, p.name FROM players p WHERE "
                        + "(EXISTS (SELECT 1 FROM team_players t WHERE t.player_id = p.id AND t.team_id = ?) "
                        + "OR EXISTS (SELECT 1 FROM scorers s WHERE s.player_id = p.id AND s.team_id = ?)) "
                        + "AND NOT EXISTS (SELECT 1 FROM player_sources x WHERE x.player_id = p.id AND x.source = ?)",
                        rs -> new Object[] {rs.getLong(1), rs.getString(2)}, teamId, teamId, source);
                id[0] = team.stream().filter(p -> GoalStats.sameName(want, FootballTables.plainName((String) p[1])))
                        .map(p -> (Long) p[0]).findFirst().orElseGet(() -> {
                            store.update("INSERT INTO players (name) VALUES (?)", name);
                            return store.first("SELECT last_insert_rowid()", rs -> rs.getLong(1)).orElseThrow();
                        });
                store.update("INSERT OR REPLACE INTO player_sources (source, source_id, player_id) VALUES (?, ?, ?)",
                        source, sourceId, id[0]);
            });
            return id[0];
        }
    }

    /**
     * Saves who a player is and his season at a team, from GOAL API's fields as GoalStats.playerStats gives them
     * (text): position, age and photo on the player; shirt, captain, injured and the team he's filed under on his
     * team; every number as a stat (replacing his stats at that team).
     */
    public void saveAtTeam(long teamId, long playerId, String name, String position, Map<String, Object> stats,
                           boolean lookedUp) {
        Map<String, Object> st = stats == null ? Map.of() : stats;
        store.transaction(() -> {
            store.update("UPDATE players SET name = ?, position = COALESCE(?, position), age = COALESCE(?, age), "
                    + "image = COALESCE(?, image) WHERE id = ?", name, position, whole(st.get("age")), Json.text(st.get("image")),
                    playerId);
            store.update("INSERT OR REPLACE INTO team_players (team_id, player_id, shirt, captain, injured, filed_under, looked_up) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", teamId, playerId, whole(st.get("number")), yes(st.get("isCaptain")),
                    yes(st.get("injured")), Json.text(st.get("filedUnder")), lookedUp ? 1 : 0);
            store.update("DELETE FROM player_season_stats WHERE player_id = ? AND team_id = ?", playerId, teamId);
            st.forEach((k, v) -> {
                Double n = number(v);
                if (!NOT_STATS.contains(k) && n != null) {
                    store.update("INSERT INTO player_season_stats (player_id, team_id, stat, value) VALUES (?, ?, ?, ?)",
                            playerId, teamId, k, n);
                }
            });
        });
    }

    /**
     * A team's players as the pages read them: [{id, name, position, games, goals, shots, stats}] by name, stats as
     * GOAL API gave them (text: numbers, plus number, age, isCaptain, injured, filedUnder, image).
     */
    public List<Map<String, Object>> squad(long teamId) {
        List<Map<String, Object>> list = store.query("SELECT p.id, p.name, p.position, p.age, p.image, t.shirt, t.captain, "
                + "t.injured, t.filed_under FROM team_players t JOIN players p ON p.id = t.player_id WHERE t.team_id = ? "
                + "ORDER BY p.name", rs -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", rs.getLong("id"));
                    p.put("name", rs.getString("name"));
                    p.put("position", rs.getString("position"));
                    Map<String, Object> st = new LinkedHashMap<>();
                    putText(st, "number", Store.longOrNull(rs, "shirt"));
                    putText(st, "age", Store.longOrNull(rs, "age"));
                    Long captain = Store.longOrNull(rs, "captain"), injured = Store.longOrNull(rs, "injured");
                    if (captain != null) {
                        st.put("isCaptain", captain == 1 ? "true" : "false");
                    }
                    if (injured != null) {
                        st.put("injured", injured == 1 ? "Yes" : "No");
                    }
                    putText(st, "filedUnder", rs.getString("filed_under"));
                    putText(st, "image", rs.getString("image"));
                    p.put("stats", st);
                    return p;
                }, teamId);
        for (Map<String, Object> p : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> st = (Map<String, Object>) p.get("stats");
            store.query("SELECT stat, value FROM player_season_stats WHERE player_id = ? AND team_id = ? ORDER BY stat", rs -> {
                st.put(rs.getString(1), text(rs.getDouble(2)));
                return null;
            }, p.get("id"), teamId);
            p.put("games", whole(st.get("matchPlayed")));
            p.put("goals", whole(st.get("goals")));
            p.put("shots", whole(st.get("shotsTotal")));
        }
        return list;
    }

    private static void putText(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, String.valueOf(v));
        }
    }

    /** A stat as GOAL API wrote it: "12", "7.25". */
    static String text(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    static Double number(Object o) {
        try {
            String s = Json.text(o);
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long whole(Object o) {
        Double d = number(o);
        return d == null ? null : Math.round(d);
    }

    /** "Yes" / "true" / "1" as 1, "No" / "false" / "0" as 0, else null. */
    static Integer yes(Object o) {
        String s = Json.text(o);
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().matches("(?i)yes|true|1") ? 1 : 0;
    }
}
