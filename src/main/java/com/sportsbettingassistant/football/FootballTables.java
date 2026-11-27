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
 * Football stats in proper tables: competitions, teams (with badges and country), which teams are in which
 * competition, matches (results and fixtures), league tables, top scorers, squads and head to head.
 * <p>
 * GoalStats saves GOAL API's answers through here, in the JSON shapes football-data.org used (the app's first
 * stats source); the pages get the same shapes back, built from the tables, so the pages didn't have to change.
 */
@Component
public class FootballTables {

    private final Store store;
    private final Games games;

    public FootballTables(Store store) {
        this.store = store;
        this.games = new Games(store);
    }

    // ---------------------------------------------------------------- saving API answers

    /** Saves a /competitions/{code}/matches answer (results or fixtures). */
    public void saveMatches(String code, String status, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            saveCompetition(code, Json.map(body.get("competition")));
            for (Map<String, Object> m : Json.maps(body.get("matches"))) {
                long apiId = Json.number(m.get("id"));
                Map<String, Object> home = Json.map(m.get("homeTeam")), away = Json.map(m.get("awayTeam"));
                Long homeId = whole(home.get("id")), awayId = whole(away.get("id"));
                if (apiId < 0 || homeId == null || awayId == null) {
                    continue;
                }
                saveTeam(home, null);
                saveTeam(away, null);
                // the game: found by GOAL API's apiId, else the same teams near this kick-off (an odds event seen
                // first), else new
                Optional<Long> game = games.link(Games.GOAL_NUMBER, String.valueOf(apiId), code, Json.text(m.get("utcDate")),
                        homeId, awayId);
                if (game.isEmpty()) {
                    continue;
                }
                games.alsoKnownAs(game.get(), Games.GOAL, Json.text(m.get("goalId")));
                Map<String, Object> score = Json.map(m.get("score"));
                Map<String, Object> ft = Json.map(score.get("fullTime")), ht = Json.map(score.get("halfTime"));
                store.update("UPDATE games SET competition = ?, kickoff_at = ?, status = ?, stage = ?, grp = ?, matchday = ?, "
                        + "home_team_id = ?, away_team_id = ?, home_goals = ?, away_goals = ?, ht_home = ?, ht_away = ? "
                        + "WHERE id = ?",
                        code, Json.text(m.get("utcDate")), Json.text(m.get("status")), Json.text(m.get("stage")),
                        Json.text(m.get("group")), whole(m.get("matchday")), homeId, awayId,
                        whole(ft.get("home")), whole(ft.get("away")), whole(ht.get("home")), whole(ht.get("away")),
                        game.get());
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

    /**
     * Saves a /teams/{id} answer's squad: every player and his position (replaces that team's squad, but keeps the
     * players we found elsewhere in GOAL API, see addToSquad, unless the squad now has them itself).
     */
    public void saveSquad(long teamId, String apiJson) {
        Map<String, Object> body = Json.parse(apiJson);
        store.transaction(() -> {
            store.update("DELETE FROM squads WHERE team_id = ? AND COALESCE(added, 0) = 0", teamId);
            for (Map<String, Object> p : Json.maps(body.get("squad"))) {
                long playerId = Json.number(p.get("id"));
                String name = Json.text(p.get("name"));
                if (playerId < 0 || name == null) {
                    continue;
                }
                store.update("INSERT OR REPLACE INTO squads (team_id, player_id, name, position, games, goals, shots, stats) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", teamId, playerId, name, position(p),
                        count(p.get("games")), count(p.get("goals")), count(p.get("shots")),
                        p.get("stats") == null ? null : Json.write(p.get("stats")));
            }
            // an added player the squad now lists itself (under another id): keep the squad's own entry
            store.update("DELETE FROM squads WHERE team_id = ? AND added = 1 AND lower(name) IN "
                    + "(SELECT lower(name) FROM squads WHERE team_id = ? AND COALESCE(added, 0) = 0)", teamId, teamId);
            store.markRefreshed("squad/" + teamId, Instant.now());
            store.markRefreshed("squad-photos/" + teamId, Instant.now());
        });
    }

    /** {"squad":[{"id","name","position","games","goals","shots"}]} by name, if saved. */
    public Optional<String> squadJson(long teamId) {
        if (store.refreshedAt("squad/" + teamId).isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> list = store.query("SELECT player_id, name, position, games, goals, shots, stats FROM squads "
                + "WHERE team_id = ? ORDER BY name", rs -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", rs.getLong(1));
            p.put("name", rs.getString(2));
            p.put("position", rs.getString(3));
            p.put("games", Store.longOrNull(rs, "games"));   // null: saved before these were kept
            p.put("goals", Store.longOrNull(rs, "goals"));
            p.put("shots", Store.longOrNull(rs, "shots"));
            String stats = rs.getString("stats");
            p.put("stats", stats == null ? null : Json.parse(stats));   // his season stats, for the player pop-up
            return p;
        }, teamId);
        return Optional.of(Json.write(Map.of("squad", list)));
    }

    /**
     * Top scorers (in the saved scorer lists) of these teams who aren't in their team's saved squad: GOAL API files
     * some players (often internationals, e.g. Cole Palmer under England) under another team. [{teamId, teamName,
     * name}], skipping ones looked up in the last 3 days (since the squad was last saved: a squad saved before
     * looked-up players were kept could have dropped them).
     */
    public List<Map<String, Object>> scorersMissingFromSquads(List<Long> teamIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant recently = Instant.now().minus(java.time.Duration.ofDays(3));
        for (long teamId : teamIds) {
            if (store.refreshedAt("squad/" + teamId).isEmpty()) {
                continue;
            }
            // a player added by an older lookup (before LOOKUP's version) is looked up again: those could carry
            // another team's numbers (his England games) as his club season
            List<String> squad = new ArrayList<>();
            for (Map.Entry<String, Boolean> row : store.query("SELECT name, COALESCE(added, 0) FROM squads WHERE team_id = ?",
                    rs -> Map.entry(rs.getString(1), rs.getInt(2) == 1), teamId)) {
                if (!row.getValue() || store.refreshedAt(lookupName(teamId, row.getKey())).isPresent()) {
                    squad.add(plainName(row.getKey()));
                }
            }
            String teamName = store.first("SELECT name FROM teams WHERE id = ?", rs -> rs.getString(1), teamId).orElse(null);
            for (String name : store.query("SELECT DISTINCT player FROM scorers WHERE team_id = ? AND player IS NOT NULL",
                    rs -> rs.getString(1), teamId)) {
                Instant saved = store.refreshedAt("squad/" + teamId).orElse(Instant.EPOCH);
                boolean looked = store.refreshedAt(lookupName(teamId, name))
                        .map(t -> t.isAfter(recently) && !t.isBefore(saved)).orElse(false);
                if (!squad.contains(plainName(name)) && !looked) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("teamId", teamId);
                    m.put("teamName", teamName);
                    m.put("name", name);
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Adds a player found elsewhere in GOAL API to a team's saved squad (position, games, goals, shots), and notes
     * that he was looked up (found or not) so he isn't looked up again for 3 days.
     */
    public void addToSquad(long teamId, String scorerName, Map<String, Object> player) {
        store.transaction(() -> {
            if (player != null) {
                store.update("INSERT OR REPLACE INTO squads (team_id, player_id, name, position, games, goals, shots, stats, added) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)", teamId, Json.number(player.get("id")), Json.text(player.get("name")),
                        Json.text(player.get("position")), count(player.get("games")), count(player.get("goals")),
                        count(player.get("shots")), player.get("stats") == null ? null : Json.write(player.get("stats")));
            }
            store.markRefreshed(lookupName(teamId, scorerName), Instant.now());
        });
    }

    /** Player lookups (see addToSquad); v2: another team's numbers are no longer kept as his club season. */
    private static final String LOOKUP = "player-lookup-v2/";

    private static String lookupName(long teamId, String name) {
        return LOOKUP + teamId + "/" + plainName(name);
    }

    /** A name to compare: no accents, lower case, single spaces ("Pedro João" and "Pedro Joao" match). */
    static String plainName(String name) {
        if (name == null) {
            return "";
        }
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** When a team's squad was last saved with its players' games and goals (older squads count as not saved). */
    public Optional<Instant> squadSavedAt(long teamId) {
        return store.refreshedAt("squad-photos/" + teamId);
    }

    /** A count from the API (number or text), or null. */
    private static Long count(Object o) {
        long n = Json.number(o);
        return n < 0 ? null : n;
    }

    /** Teams with a saved fixture (SCHEDULED or TIMED) kicking off between now and {@code days} from now. */
    public List<Long> teamsPlayingWithin(int days) {
        Instant now = Instant.now();
        return store.query("SELECT DISTINCT t FROM (SELECT home_team_id AS t, kickoff_at, status FROM games UNION ALL "
                        + "SELECT away_team_id, kickoff_at, status FROM games) WHERE t IS NOT NULL AND status IN ('SCHEDULED', 'TIMED') "
                        + "AND kickoff_at >= ? AND kickoff_at <= ? ORDER BY t",
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

    // ---------------------------------------------------------------- GOAL API ids, names, tables

    /** Remembers a team's GOAL API id (e.g. "cmr7hvaw63yv...") for our id. */
    public void saveGoalId(String goalId, long teamId) {
        store.update("INSERT OR REPLACE INTO goal_teams (goal_id, team_id) VALUES (?, ?)", goalId, teamId);
    }

    /** Our team id for a GOAL API team id, if that team was saved. */
    public Optional<Long> teamIdForGoal(String goalId) {
        return goalId == null ? Optional.empty()
                : store.first("SELECT team_id FROM goal_teams WHERE goal_id = ?", rs -> rs.getLong(1), goalId);
    }

    /** A team's GOAL API id, if known. */
    public Optional<String> goalId(long teamId) {
        return store.first("SELECT goal_id FROM goal_teams WHERE team_id = ? LIMIT 1", rs -> rs.getString(1), teamId);
    }

    /** A saved fixture's home and away team ids. */
    public Optional<long[]> matchTeams(long matchId) {
        return store.first("SELECT home_team_id, away_team_id FROM games WHERE id = ?", rs -> new long[] {rs.getLong(1), rs.getLong(2)}, matchId);
    }

    /** A team by GOAL API's name for it ("Manchester City"), preferring the competition's own teams. */
    public Optional<Map<String, Object>> teamByName(String code, String name) {
        String same = "(LOWER(t.short_name) = LOWER(?) OR LOWER(t.name) = LOWER(?))";
        Optional<Map<String, Object>> t = store.first("SELECT t.* FROM teams t JOIN competition_teams c ON c.team_id = t.id "
                + "WHERE c.competition = ? AND " + same + " LIMIT 1", FootballTables::team, code, name, name);
        return t.isPresent() ? t : store.first("SELECT t.* FROM teams t WHERE " + same + " LIMIT 1", FootballTables::team, name, name);
    }

    /**
     * Saves a league table (replaces that competition's). Rows: teamId, teamName, position, played, won, draw,
     * lost, goalsFor, goalsAgainst, points, group.
     */
    public void saveStandings(String code, List<Map<String, Object>> rows) {
        store.transaction(() -> {
            store.update("DELETE FROM standings WHERE competition = ?", code);
            for (Map<String, Object> r : rows) {
                store.update("INSERT OR REPLACE INTO standings (competition, team_id, team_name, position, played, won, draw, "
                        + "lost, goals_for, goals_against, points, grp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        code, r.get("teamId"), r.get("teamName"), r.get("position"), r.get("played"), r.get("won"),
                        r.get("draw"), r.get("lost"), r.get("goalsFor"), r.get("goalsAgainst"), r.get("points"), r.get("group"));
            }
            store.markRefreshed("standings/" + code, Instant.now());
        });
    }

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

    /** Every saved team, from all competitions. */
    public List<Map<String, Object>> allTeams() {
        List<Map<String, Object>> teams = store.query("SELECT * FROM teams ORDER BY name", rs -> {
            Map<String, Object> t = team(rs);
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("name", rs.getString("country"));
            t.put("area", area);
            return t;
        });
        return teams;
    }

    /** Clubs (or countries) that have played in a competition, for the Favourites list. */
    public List<Map<String, Object>> teamsPlayingIn(String code) {
        return store.query("SELECT * FROM teams WHERE id IN (SELECT home_team_id FROM games WHERE competition = ? "
                + "UNION SELECT away_team_id FROM games WHERE competition = ?) ORDER BY name", FootballTables::team, code, code);
    }

    /** {"matches":[...]} for a competition and status (FINISHED or SCHEDULED), if they were saved. */
    public Optional<String> matchesJson(String code, String status) {
        if (store.refreshedAt(matchesName(code, status)).isEmpty()) {
            return Optional.empty();
        }
        // football-data's "SCHEDULED" fixtures include ones with a confirmed time (status TIMED)
        String statuses = "SCHEDULED".equals(status) ? "('SCHEDULED', 'TIMED')" : "('" + status.replaceAll("[^A-Z_]", "") + "')";
        return Optional.of(Json.write(Map.of("matches", matches(
                "WHERE m.competition = ? AND m.status IN " + statuses + " ORDER BY m.kickoff_at", code))));
    }

    /** A team's last 30 finished matches in every saved competition, newest first, as {"matches":[...]}. */
    public Optional<String> teamResults(long teamId) {
        List<Map<String, Object>> list = matches("WHERE m.status = 'FINISHED' AND (m.home_team_id = ? OR m.away_team_id = ?) "
                + "ORDER BY m.kickoff_at DESC LIMIT 30", teamId, teamId);
        return list.isEmpty() ? Optional.empty() : Optional.of(Json.write(Map.of("matches", list)));
    }

    /**
     * A league table: GOAL API's own if saved, else worked out from saved results (league / group games only,
     * not knockouts), in football-data.org's standings format: points, then goal difference, then goals scored.
     */
    public Optional<String> standingsJson(String code) {
        Optional<String> saved = savedStandingsJson(code);
        if (saved.isPresent()) {
            return saved;
        }
        if (store.refreshedAt(matchesName(code, "FINISHED")).isEmpty()) {
            return Optional.empty();
        }
        String games = "SELECT %s AS team_id, %s AS gf, %s AS ga FROM games WHERE competition = ? AND status = 'FINISHED' "
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

    /** GOAL API's own table for a competition (same shape as above), if one was saved. */
    private Optional<String> savedStandingsJson(String code) {
        List<Map<String, Object>> table = store.query("SELECT s.*, t.name, t.short_name FROM standings s "
                + "LEFT JOIN teams t ON t.id = s.team_id WHERE s.competition = ? ORDER BY s.grp, s.position", rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Map<String, Object> team = new LinkedHashMap<>();
                    team.put("id", rs.getLong("team_id"));
                    String name = rs.getString("name");
                    team.put("name", name != null ? name : rs.getString("team_name"));
                    team.put("shortName", rs.getString("short_name") != null ? rs.getString("short_name") : rs.getString("team_name"));
                    row.put("position", rs.getInt("position"));
                    row.put("team", team);
                    row.put("playedGames", rs.getInt("played"));
                    row.put("won", rs.getInt("won"));
                    row.put("draw", rs.getInt("draw"));
                    row.put("lost", rs.getInt("lost"));
                    row.put("points", rs.getInt("points"));
                    row.put("goalsFor", rs.getInt("goals_for"));
                    row.put("goalsAgainst", rs.getInt("goals_against"));
                    row.put("goalDifference", rs.getInt("goals_for") - rs.getInt("goals_against"));
                    return row;
                }, code);
        if (table.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("type", "TOTAL");
        total.put("table", table);
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("code", code);
        comp.put("name", competitionName(code));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("competition", comp);
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

    /** Saves head to head for a fixture ({"aggregates":{...},"matches":[...]}): the totals and the last few meetings. */
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
                + "FROM games m LEFT JOIN competitions c ON c.code = m.competition "
                + "LEFT JOIN teams h ON h.id = m.home_team_id LEFT JOIN teams a ON a.id = m.away_team_id " + where,
                FootballTables::match, args);
    }

    private static Map<String, Object> match(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("utcDate", rs.getString("kickoff_at"));
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
