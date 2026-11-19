package com.sportsbettingassistant.football;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * football-data.org data in proper tables: competitions, teams (with crests and country), which teams are in
 * which competition, matches (results and fixtures) and top scorers.
 * <p>
 * Refresh Stats and the crest lists save the API's JSON through here; the pages get the same JSON shapes back,
 * built from the tables (only the fields they use), so nothing in the pages had to change.
 */
@Component
public class FootballTables {

    private final Store store;

    public FootballTables(Store store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- saving API answers

    /** Saves a /competitions/{code}/matches answer (results or fixtures). */
    public void saveMatches(String code, String status, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            saveCompetition(code, Json.map(body.get("competition")));
            for (Map<String, Object> m : Json.maps(body.get("matches"))) {
                long id = Json.number(m.get("id"));
                if (id < 0) {
                    continue;
                }
                Map<String, Object> home = Json.map(m.get("homeTeam")), away = Json.map(m.get("awayTeam"));
                saveTeam(home, null);
                saveTeam(away, null);
                Map<String, Object> score = Json.map(m.get("score"));
                Map<String, Object> ft = Json.map(score.get("fullTime")), ht = Json.map(score.get("halfTime"));
                store.update("INSERT OR REPLACE INTO matches (id, competition, utc_date, status, stage, grp, matchday, "
                        + "home_team_id, away_team_id, home_goals, away_goals, ht_home, ht_away) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id, code, Json.text(m.get("utcDate")), Json.text(m.get("status")), Json.text(m.get("stage")),
                        Json.text(m.get("group")), whole(m.get("matchday")), whole(home.get("id")), whole(away.get("id")),
                        whole(ft.get("home")), whole(ft.get("away")), whole(ht.get("home")), whole(ht.get("away")));
            }
            store.markRefreshed(matchesName(code, status), Instant.now());
        });
    }

    /** Saves a /competitions/{code}/scorers answer (replaces that competition's list). */
    public void saveScorers(String code, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            saveCompetition(code, Json.map(body.get("competition")));
            store.update("DELETE FROM scorers WHERE competition = ?", code);
            for (Map<String, Object> s : Json.maps(body.get("scorers"))) {
                Map<String, Object> player = Json.map(s.get("player")), team = Json.map(s.get("team"));
                long playerId = Json.number(player.get("id"));
                if (playerId < 0) {
                    continue;
                }
                saveTeam(team, null);
                store.update("INSERT OR REPLACE INTO scorers (competition, player_id, player, team_id, goals, assists, "
                        + "penalties, games, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        code, playerId, Json.text(player.get("name")), whole(team.get("id")), whole(s.get("goals")),
                        whole(s.get("assists")), whole(s.get("penalties")), whole(s.get("playedMatches")),
                        position(player));
            }
            store.markRefreshed("scorers/" + code, Instant.now());
        });
    }

    /** Saves a /teams/{id} answer's squad: every player and his position (replaces that team's squad). */
    public void saveSquad(long teamId, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            store.update("DELETE FROM squads WHERE team_id = ?", teamId);
            for (Map<String, Object> p : Json.maps(body.get("squad"))) {
                long playerId = Json.number(p.get("id"));
                String name = Json.text(p.get("name"));
                if (playerId < 0 || name == null) {
                    continue;
                }
                store.update("INSERT OR REPLACE INTO squads (team_id, player_id, name, position) VALUES (?, ?, ?, ?)",
                        teamId, playerId, name, position(p));
            }
            store.markRefreshed("squad/" + teamId, Instant.now());
        });
    }

    /** {"squad":[{"id","name","position"}]} by name, if saved. */
    public Optional<String> squadJson(long teamId) {
        if (store.refreshedAt("squad/" + teamId).isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> list = store.query("SELECT player_id, name, position FROM squads WHERE team_id = ? ORDER BY name", rs -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", rs.getLong(1));
            p.put("name", rs.getString(2));
            p.put("position", rs.getString(3));
            return p;
        }, teamId);
        return Optional.of(Json.write(Map.of("squad", list)));
    }

    /** When a team's squad was last saved. */
    public Optional<Instant> squadSavedAt(long teamId) {
        return store.refreshedAt("squad/" + teamId);
    }

    /** Teams with a saved fixture (SCHEDULED or TIMED) kicking off between now and {@code days} from now. */
    public List<Long> teamsPlayingWithin(int days) {
        Instant now = Instant.now();
        return store.query("SELECT DISTINCT t FROM (SELECT home_team_id AS t, utc_date, status FROM matches UNION ALL "
                        + "SELECT away_team_id, utc_date, status FROM matches) WHERE t IS NOT NULL AND status IN ('SCHEDULED', 'TIMED') "
                        + "AND utc_date >= ? AND utc_date <= ? ORDER BY t",
                rs -> rs.getLong(1), now.toString(), now.plus(java.time.Duration.ofDays(days)).toString());
    }

    /** A player's role: "position" (e.g. "Attacker"), or "section" (e.g. "Centre-Forward") when position is empty. */
    static String position(Map<String, Object> player) {
        String position = Json.text(player.get("position"));
        return position != null && !position.isBlank() ? position : Json.text(player.get("section"));
    }

    /** Saves a /competitions/{code}/teams answer: the teams with crests and country, and who is in it. */
    public void saveTeams(String code, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            saveCompetition(code, Json.map(body.get("competition")));
            store.update("DELETE FROM competition_teams WHERE competition = ?", code);
            for (Map<String, Object> t : Json.maps(body.get("teams"))) {
                if (saveTeam(t, Json.text(Json.map(t.get("area")).get("name")))) {
                    store.update("INSERT OR IGNORE INTO competition_teams (competition, team_id) VALUES (?, ?)",
                            code, Json.number(t.get("id")));
                }
            }
            store.markRefreshed("teams/" + code, Instant.now());
        });
    }

    private void saveCompetition(String code, Map<String, Object> c) {
        store.update("INSERT INTO competitions (code, name, type) VALUES (?, ?, ?) ON CONFLICT(code) DO UPDATE SET "
                + "name = COALESCE(excluded.name, name), type = COALESCE(excluded.type, type)",
                code, Json.text(c.get("name")), Json.text(c.get("type")));
    }

    /** Adds or updates a team (keeps what we already know if this answer leaves something out). */
    private boolean saveTeam(Map<String, Object> t, String country) {
        long id = Json.number(t.get("id"));
        String name = Json.text(t.get("name"));
        if (id < 0 || name == null) {
            return false;
        }
        store.update("INSERT INTO teams (id, name, short_name, tla, crest, country) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET name = excluded.name, short_name = COALESCE(excluded.short_name, short_name), "
                + "tla = COALESCE(excluded.tla, tla), crest = COALESCE(excluded.crest, crest), "
                + "country = COALESCE(excluded.country, country)",
                id, name, Json.text(t.get("shortName")), Json.text(t.get("tla")), Json.text(t.get("crest")), country);
        return true;
    }

    // ---------------------------------------------------------------- reading, in the API's shapes

    /** {"teams":[{id, name, shortName, tla, crest, area:{name}}]} for a competition, if its crests were saved. */
    public Optional<String> teamsJson(String code) {
        if (store.refreshedAt("teams/" + code).isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> teams = store.query("SELECT t.* FROM teams t JOIN competition_teams c ON c.team_id = t.id "
                + "WHERE c.competition = ? ORDER BY t.name", rs -> {
                    Map<String, Object> t = team(rs);
                    Map<String, Object> area = new LinkedHashMap<>();
                    area.put("name", rs.getString("country"));
                    t.put("area", area);
                    return t;
                }, code);
        return Optional.of(Json.write(Map.of("teams", teams)));
    }

    /** Clubs (or countries) that have played in a competition, for the Favourites list. */
    public List<Map<String, Object>> teamsPlayingIn(String code) {
        return store.query("SELECT * FROM teams WHERE id IN (SELECT home_team_id FROM matches WHERE competition = ? "
                + "UNION SELECT away_team_id FROM matches WHERE competition = ?) ORDER BY name", FootballTables::team, code, code);
    }

    /** {"matches":[...]} for a competition and status (FINISHED or SCHEDULED), if they were saved. */
    public Optional<String> matchesJson(String code, String status) {
        if (store.refreshedAt(matchesName(code, status)).isEmpty()) {
            return Optional.empty();
        }
        // football-data's "SCHEDULED" fixtures include ones with a confirmed time (status TIMED)
        String statuses = "SCHEDULED".equals(status) ? "('SCHEDULED', 'TIMED')" : "('" + status.replaceAll("[^A-Z_]", "") + "')";
        return Optional.of(Json.write(Map.of("matches", matches(
                "WHERE m.competition = ? AND m.status IN " + statuses + " ORDER BY m.utc_date", code))));
    }

    /** A team's last 30 finished matches in every saved competition, newest first, as {"matches":[...]}. */
    public Optional<String> teamResults(long teamId) {
        List<Map<String, Object>> list = matches("WHERE m.status = 'FINISHED' AND (m.home_team_id = ? OR m.away_team_id = ?) "
                + "ORDER BY m.utc_date DESC LIMIT 30", teamId, teamId);
        return list.isEmpty() ? Optional.empty() : Optional.of(Json.write(Map.of("matches", list)));
    }

    /**
     * A league table worked out from saved results (league / group games only, not knockouts), in
     * football-data.org's standings format: points, then goal difference, then goals scored.
     */
    public Optional<String> standingsJson(String code) {
        if (store.refreshedAt(matchesName(code, "FINISHED")).isEmpty()) {
            return Optional.empty();
        }
        String games = "SELECT %s AS team_id, %s AS gf, %s AS ga FROM matches WHERE competition = ? AND status = 'FINISHED' "
                + "AND home_goals IS NOT NULL AND away_goals IS NOT NULL "
                + "AND (stage IS NULL OR stage IN ('REGULAR_SEASON', 'LEAGUE_STAGE', 'GROUP_STAGE'))";
        List<Map<String, Object>> table = store.query("WITH r AS ("
                + games.formatted("home_team_id", "home_goals", "away_goals") + " UNION ALL "
                + games.formatted("away_team_id", "away_goals", "home_goals") + ") "
                + "SELECT r.team_id, t.name, t.short_name, COUNT(*) AS played, SUM(gf > ga) AS won, SUM(gf = ga) AS draw, "
                + "SUM(gf < ga) AS lost, SUM(CASE WHEN gf > ga THEN 3 WHEN gf = ga THEN 1 ELSE 0 END) AS points, "
                + "SUM(gf) AS goals_for, SUM(ga) AS goals_against, SUM(gf - ga) AS goal_difference "
                + "FROM r LEFT JOIN teams t ON t.id = r.team_id GROUP BY r.team_id "
                + "ORDER BY points DESC, goal_difference DESC, goals_for DESC, t.name", rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Map<String, Object> team = new LinkedHashMap<>();
                    team.put("id", rs.getLong("team_id"));
                    team.put("name", rs.getString("name"));
                    team.put("shortName", rs.getString("short_name"));
                    row.put("position", 0);
                    row.put("team", team);
                    row.put("playedGames", rs.getInt("played"));
                    row.put("won", rs.getInt("won"));
                    row.put("draw", rs.getInt("draw"));
                    row.put("lost", rs.getInt("lost"));
                    row.put("points", rs.getInt("points"));
                    row.put("goalsFor", rs.getInt("goals_for"));
                    row.put("goalsAgainst", rs.getInt("goals_against"));
                    row.put("goalDifference", rs.getInt("goal_difference"));
                    return row;
                }, code, code);
        for (int i = 0; i < table.size(); i++) {
            table.get(i).put("position", i + 1);
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("type", "TOTAL");
        total.put("table", table);
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("code", code);
        comp.put("name", competitionName(code));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("competition", comp);
        out.put("calculated", true);
        out.put("standings", List.of(total));
        return Optional.of(Json.write(out));
    }

    /** {"competition":{...},"scorers":[...]} most goals first, if saved. */
    public Optional<String> scorersJson(String code) {
        if (store.refreshedAt("scorers/" + code).isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> list = store.query("SELECT s.*, t.name AS team_name, t.short_name AS team_short FROM scorers s "
                + "LEFT JOIN teams t ON t.id = s.team_id WHERE s.competition = ? ORDER BY s.goals DESC, s.player", rs -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    Map<String, Object> player = new LinkedHashMap<>();
                    player.put("id", rs.getLong("player_id"));
                    player.put("name", rs.getString("player"));
                    player.put("position", rs.getString("position"));   // null until the next Refresh Stats
                    s.put("player", player);
                    Map<String, Object> team = new LinkedHashMap<>();
                    team.put("id", Store.longOrNull(rs, "team_id"));
                    team.put("name", rs.getString("team_name"));
                    team.put("shortName", rs.getString("team_short"));
                    s.put("team", team);
                    s.put("goals", Store.longOrNull(rs, "goals"));
                    s.put("assists", Store.longOrNull(rs, "assists"));
                    s.put("penalties", Store.longOrNull(rs, "penalties"));
                    s.put("playedMatches", Store.longOrNull(rs, "games"));
                    return s;
                }, code);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("code", code);
        comp.put("name", competitionName(code));
        out.put("competition", comp);
        out.put("scorers", list);
        return Optional.of(Json.write(out));
    }

    // ---------------------------------------------------------------- head to head

    /** Saves a /matches/{id}/head2head answer for a fixture: the totals and the last few meetings. */
    public void saveHeadToHead(long fixtureId, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        Map<String, Object> agg = Json.map(body.get("aggregates"));
        store.transaction(() -> {
            store.update("DELETE FROM head_to_head_matches WHERE match_id = ?", fixtureId);
            store.update("INSERT OR REPLACE INTO head_to_head (match_id, number_of_matches, total_goals, fetched_at) "
                    + "VALUES (?, ?, ?, ?)", fixtureId, whole(agg.get("numberOfMatches")), whole(agg.get("totalGoals")), Instant.now());
            int position = 0;
            for (Map<String, Object> m : Json.maps(body.get("matches"))) {
                Map<String, Object> ft = Json.map(Json.map(m.get("score")).get("fullTime"));
                store.update("INSERT INTO head_to_head_matches (match_id, position, utc_date, status, competition, home, away, "
                        + "home_goals, away_goals) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", fixtureId, position++,
                        Json.text(m.get("utcDate")), Json.text(m.get("status")), Json.text(Json.map(m.get("competition")).get("name")),
                        shortName(Json.map(m.get("homeTeam"))), shortName(Json.map(m.get("awayTeam"))),
                        whole(ft.get("home")), whole(ft.get("away")));
            }
        });
    }

    /** When head to head was last fetched for a fixture, if it has been. */
    public Optional<Instant> headToHeadSavedAt(long fixtureId) {
        return store.first("SELECT fetched_at FROM head_to_head WHERE match_id = ?", rs -> Instant.ofEpochMilli(rs.getLong(1)), fixtureId);
    }

    /** {"aggregates":{numberOfMatches, totalGoals}, "matches":[...]} as the match page reads it, if saved. */
    public Optional<String> headToHeadJson(long fixtureId) {
        return store.first("SELECT * FROM head_to_head WHERE match_id = ?", rs -> {
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("numberOfMatches", Store.longOrNull(rs, "number_of_matches"));
            agg.put("totalGoals", Store.longOrNull(rs, "total_goals"));
            return agg;
        }, fixtureId).map(agg -> {
            List<Map<String, Object>> meetings = store.query("SELECT * FROM head_to_head_matches WHERE match_id = ? ORDER BY position", rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("utcDate", rs.getString("utc_date"));
                m.put("status", rs.getString("status"));
                m.put("competition", nameOnly(rs.getString("competition")));
                m.put("homeTeam", nameOnly(rs.getString("home")));
                m.put("awayTeam", nameOnly(rs.getString("away")));
                m.put("score", Map.of("fullTime", goals(Store.longOrNull(rs, "home_goals"), Store.longOrNull(rs, "away_goals"))));
                return m;
            }, fixtureId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("aggregates", agg);
            out.put("matches", meetings);
            return Json.write(out);
        });
    }

    private static String shortName(Map<String, Object> team) {
        String s = Json.text(team.get("shortName"));
        return s != null ? s : Json.text(team.get("name"));
    }

    private static Map<String, Object> nameOnly(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    /** The countries of a competition's teams (from its crest list), e.g. to find which leagues they play in. */
    public List<String> countries(String code) {
        return store.query("SELECT DISTINCT t.country FROM teams t JOIN competition_teams c ON c.team_id = t.id "
                + "WHERE c.competition = ? AND t.country IS NOT NULL", rs -> rs.getString(1), code);
    }

    // ---------------------------------------------------------------- helpers

    private List<Map<String, Object>> matches(String where, Object... args) {
        return store.query("SELECT m.*, c.name AS comp_name, c.type AS comp_type, "
                + "h.name AS h_name, h.short_name AS h_short, h.tla AS h_tla, h.crest AS h_crest, "
                + "a.name AS a_name, a.short_name AS a_short, a.tla AS a_tla, a.crest AS a_crest "
                + "FROM matches m LEFT JOIN competitions c ON c.code = m.competition "
                + "LEFT JOIN teams h ON h.id = m.home_team_id LEFT JOIN teams a ON a.id = m.away_team_id " + where,
                FootballTables::match, args);
    }

    private static Map<String, Object> match(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("utcDate", rs.getString("utc_date"));
        m.put("status", rs.getString("status"));
        m.put("stage", rs.getString("stage"));
        m.put("group", rs.getString("grp"));
        m.put("matchday", Store.longOrNull(rs, "matchday"));
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("code", rs.getString("competition"));
        comp.put("name", rs.getString("comp_name"));
        comp.put("type", rs.getString("comp_type"));
        m.put("competition", comp);
        m.put("homeTeam", side(rs, "home_team_id", "h_"));
        m.put("awayTeam", side(rs, "away_team_id", "a_"));
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("fullTime", goals(Store.longOrNull(rs, "home_goals"), Store.longOrNull(rs, "away_goals")));
        score.put("halfTime", goals(Store.longOrNull(rs, "ht_home"), Store.longOrNull(rs, "ht_away")));
        m.put("score", score);
        return m;
    }

    private static Map<String, Object> side(ResultSet rs, String idColumn, String prefix) throws SQLException {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", Store.longOrNull(rs, idColumn));
        t.put("name", rs.getString(prefix + "name"));
        t.put("shortName", rs.getString(prefix + "short"));
        t.put("tla", rs.getString(prefix + "tla"));
        t.put("crest", rs.getString(prefix + "crest"));
        return t;
    }

    private static Map<String, Object> goals(Long home, Long away) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("home", home);
        g.put("away", away);
        return g;
    }

    private static Map<String, Object> team(ResultSet rs) throws SQLException {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", rs.getLong("id"));
        t.put("name", rs.getString("name"));
        t.put("shortName", rs.getString("short_name"));
        t.put("tla", rs.getString("tla"));
        t.put("crest", rs.getString("crest"));
        return t;
    }

    private String competitionName(String code) {
        return store.first("SELECT COALESCE(name, code) FROM competitions WHERE code = ?", rs -> rs.getString(1), code)
                .orElse(code);
    }

    private static Long whole(Object o) {
        long n = Json.number(o);
        return o == null || n == -1 && !"-1".equals(String.valueOf(o)) ? null : n;
    }

    static String matchesName(String code, String status) {
        return "matches/" + code + "/" + status;
    }
}
