package com.sportsbettingassistant.football;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * Stats from GOAL API (1,000 calls a day): teams and badges, results, fixtures, league tables, top scorers, squads
 * and head to head. Each answer is turned into the shapes FootballTables saves (the ones football-data.org used),
 * so the tables and the pages work as before.
 * <p>
 * Team ids are GOAL API's numeric team ids ("apiId", also the number in each badge's file name, e.g.
 * 3085_fulham.jpg), so results, tables, squads and head to head all agree. GOAL API's own team ids (e.g.
 * "cmr7hvaw63yv...") are kept in goal_teams, for the calls that need them (squads, head to head).
 */
@Service
public class GoalStats {

    static final int PAGE = 100;        // GOAL API's largest page
    static final int MAX_PAGES = 5;     // a season's results: 2-4 pages for most leagues
    static final int H2H_SHOWN = 6;     // meetings kept for the Head to Head card
    private static final ZoneId UK = ZoneId.of("Europe/London");

    private final GoalApiClient api;
    private final FootballTables tables;
    private final Store store;

    public GoalStats(GoalApiClient api, FootballTables tables, Store store) {
        this.api = api;
        this.tables = tables;
        this.store = store;
    }

    public boolean configured() {
        return api.configured();
    }

    // ---------------------------------------------------------------- calls (each saves what it gets)

    /** A competition's teams, with badges and countries (1 call). */
    public void saveTeams(String code) {
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map<String, Object> t : data(get("/leagues/" + league(code) + "/teams", Map.of("limit", String.valueOf(PAGE))))) {
            String badge = Json.text(t.get("badge")), name = Json.text(t.get("name"));
            Long id = teamId(t.get("apiId"), badge);
            if (id == null || name == null) {
                continue;
            }
            String goalId = Json.text(t.get("id"));
            if (goalId != null) {
                tables.saveGoalId(goalId, id);
            }
            teams.add(team(id, name, badge, Json.text(t.get("country"))));
        }
        tables.saveTeams(code, Json.write(Map.of("competition", competition(code), "teams", teams)));
    }

    /** This season's results, newest first (1 call per 100 games; stops at last season). Returns the calls made. */
    public int saveResults(String code) {
        String league = league(code);
        List<Map<String, Object>> rows = new ArrayList<>();
        String season = null;
        int calls = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = get("/leagues/" + league + "/results",
                    Map.of("limit", String.valueOf(PAGE), "offset", String.valueOf(page * PAGE)));
            calls++;
            List<Map<String, Object>> list = data(body);
            boolean older = false;
            for (Map<String, Object> m : list) {
                String year = Json.text(m.get("leagueYear"));
                if (season == null) {
                    season = year;
                }
                if (season != null && year != null && !season.equals(year)) {
                    older = true;   // last season: not used for this season's form and goals
                } else if ("FINISHED".equalsIgnoreCase(Json.text(m.get("matchStatus")))) {
                    rows.add(m);
                }
            }
            if (older || list.size() < PAGE || "false".equals(String.valueOf(Json.map(body.get("pagination")).get("hasMore")))) {
                break;
            }
        }
        tables.saveMatches(code, "FINISHED", matchesJson(code, rows, "FINISHED"));
        return calls;
    }

    /** The next 30 days' fixtures (1 call). */
    public void saveFixtures(String code) {
        LocalDate today = LocalDate.now(UK);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : data(get("/leagues/" + league(code) + "/fixtures", Map.of(
                "from", today.toString(), "to", today.plusDays(30).toString(), "limit", String.valueOf(PAGE))))) {
            if (!"FINISHED".equalsIgnoreCase(Json.text(m.get("matchStatus")))) {
                rows.add(m);
            }
        }
        tables.saveMatches(code, "SCHEDULED", matchesJson(code, rows, "SCHEDULED"));
    }

    /** The league table, with every game this season (1 call). Nothing is saved if GOAL API has no table. */
    public void saveStandings(String code) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : data(get("/leagues/" + league(code) + "/standings", Map.of()))) {
            Long teamId = tables.teamIdForGoal(Json.text(r.get("teamId"))).orElse(null);
            String name = Json.text(r.get("teamName"));
            if (teamId == null && name != null) {
                teamId = tables.teamByName(code, name).map(t -> (Long) t.get("id")).orElse(null);
            }
            if (teamId == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", teamId);
            row.put("teamName", name);
            row.put("position", whole(r.get("overallLeaguePosition")));
            row.put("played", whole(r.get("overallLeaguePlayed")));
            row.put("won", whole(r.get("overallLeagueW")));
            row.put("draw", whole(r.get("overallLeagueD")));
            row.put("lost", whole(r.get("overallLeagueL")));
            row.put("goalsFor", whole(r.get("overallLeagueGF")));
            row.put("goalsAgainst", whole(r.get("overallLeagueGA")));
            row.put("points", whole(r.get("overallLeaguePTS")));
            row.put("group", Json.text(r.get("leagueRound")));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            tables.saveStandings(code, rows);
        }
    }

    /** Top scorers (1 call). GOAL API names each scorer's team, so it's matched to the competition's teams by name. */
    public void saveScorers(String code) {
        List<Map<String, Object>> scorers = new ArrayList<>();
        for (Map<String, Object> s : data(get("/leagues/" + league(code) + "/top-scorers", Map.of()))) {
            Long playerId = whole(s.get("playerKey"));
            String name = Json.text(s.get("playerName"));
            if (playerId == null || name == null) {
                continue;
            }
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("id", playerId);
            player.put("name", name);
            Map<String, Object> team = new LinkedHashMap<>();
            String teamName = Json.text(s.get("teamName"));
            Optional<Map<String, Object>> known = teamName == null ? Optional.empty() : tables.teamByName(code, teamName);
            team.put("id", known.map(t -> t.get("id")).orElse(null));
            team.put("name", known.map(t -> t.get("name")).orElse(teamName));
            team.put("shortName", known.map(t -> t.get("shortName")).orElse(teamName));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", player);
            row.put("team", team);
            row.put("goals", whole(s.get("goals")));
            row.put("assists", whole(s.get("assists")));
            row.put("penalties", whole(s.get("penaltyGoals")));
            scorers.add(row);
        }
        tables.saveScorers(code, Json.write(Map.of("competition", competition(code), "scorers", scorers)));
    }

    /**
     * A team's squad: every player, his position and his season so far (games, goals, shots) (1 call). False if we
     * don't know the team's GOAL API id.
     */
    public boolean saveSquad(long teamId) {
        Optional<String> goalId = tables.goalId(teamId);
        if (goalId.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> squad = new ArrayList<>();
        for (Map<String, Object> p : data(get("/teams/" + goalId.get() + "/players", Map.of("limit", String.valueOf(PAGE))))) {
            Long id = whole(p.get("apiId"));
            String name = Json.text(p.get("name"));
            if (id == null || name == null) {
                continue;
            }
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("id", id);
            player.put("name", name);
            player.put("position", Json.text(p.get("type")));   // "Forwards", "Midfielders"...
            player.put("games", whole(p.get("matchPlayed")));   // this season so far, for goals per game
            player.put("goals", whole(p.get("goals")));
            player.put("shots", whole(p.get("shotsTotal")));
            player.put("stats", playerStats(p));
            squad.add(player);
        }
        tables.saveSquad(teamId, Json.write(Map.of("squad", squad)));
        return true;
    }

    /** The season stats GOAL API gives for a player (the ones it filled in), for the match page's player pop-up. */
    static final List<String> PLAYER_STATS = List.of("number", "age", "isCaptain", "injured", "matchPlayed", "minutes",
            "rating", "goals", "assists", "shotsTotal", "penScored", "penMissed", "penWon", "woodworks", "keyPasses", "passes",
            "passesAccuracy", "crossesTotal", "dribbleAttempts", "dribbleSucc", "duelsTotal", "duelsWon", "tackles",
            "interceptions", "blocks", "clearances", "foulsCommitted", "dispossessed", "penCommitted", "yellowCards",
            "redCards", "substituteOut", "substitutesOnBench", "saves", "insideBoxSaves", "goalsConceded");

    static Map<String, Object> playerStats(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : PLAYER_STATS) {
            String v = Json.text(p.get(k));
            if (v != null && !v.isBlank()) {
                out.put(k, v);
            }
        }
        String team = Json.text(Json.map(p.get("team")).get("name"));
        if (team != null) {
            out.put("filedUnder", team);   // the team GOAL API has him at (e.g. England for some internationals)
        }
        String image = Json.text(p.get("image"));
        if (image != null && image.startsWith("https://media.goal-api.com/")) {
            out.put("image", image);       // his photo on GOAL API's image server, for the player pop-up
        }
        return out;
    }

    /**
     * A top scorer missing from his club's squad (GOAL API files some players under another team, e.g. Cole Palmer
     * under England): finds him by name (1 call) and adds his position, games, goals and shots to the club's squad.
     * Prefers a result at that club; else the one with that exact name, but then only who he is (position, photo,
     * age), not that team's numbers. True if he was found.
     */
    public boolean findPlayer(long teamId, String teamName, String name) {
        String want = FootballTables.plainName(name), club = FootballTables.plainName(teamName);
        // the search takes plain letters only (no accents: "Z. Tripić" is refused), and an initial ("Z. Tripic")
        // finds nothing, so a name with one is looked up by surname
        String[] parts = want.split(" ");
        boolean initial = parts.length > 1 && parts[0].matches("[a-z]\\.?");
        String query = (initial ? want.substring(want.indexOf(' ') + 1) : want).replaceAll("[^a-z0-9' -]", " ").trim();
        if (query.isEmpty()) {
            tables.addToSquad(teamId, name, null);
            return false;
        }
        List<Map<String, Object>> found = data(get("/players/search", Map.of("q", query)));
        Map<String, Object> best = null;
        for (Map<String, Object> p : found) {
            if (!sameName(want, FootballTables.plainName(Json.text(p.get("name"))))) {
                continue;
            }
            String team = FootballTables.plainName(Json.text(Json.map(p.get("team")).get("name")));
            if (best == null || (!club.isEmpty() && (team.contains(club) || club.contains(team)))) {
                best = p;
            }
        }
        Map<String, Object> player = null;
        Long id = best == null ? null : whole(best.get("apiId"));
        if (id != null) {
            String team = FootballTables.plainName(Json.text(Json.map(best.get("team")).get("name")));
            boolean atClub = !club.isEmpty() && !team.isEmpty() && (team.contains(club) || club.contains(team));
            player = new LinkedHashMap<>();
            player.put("id", id);
            player.put("name", name);   // the scorer list's spelling, so the page matches him
            player.put("position", Json.text(best.get("type")));
            Map<String, Object> stats = playerStats(best);
            if (atClub) {
                player.put("games", whole(best.get("matchPlayed")));
                player.put("goals", whole(best.get("goals")));
                player.put("shots", whole(best.get("shotsTotal")));
            } else {
                // filed under another team (e.g. England): those numbers are his games for them, not his club season
                // (Morgan Rogers: 7 games, 1 shot for England, 5 goals for Chelsea), so keep only who he is
                stats.keySet().retainAll(java.util.Set.of("number", "age", "isCaptain", "injured", "filedUnder", "image"));
            }
            player.put("stats", stats);
        }
        tables.addToSquad(teamId, name, player);
        return player != null;
    }

    /**
     * Same player name, plain (see FootballTables.plainName): exactly, or an initial for the first name ("z. tripic" and
     * "zan tripic", "z tripic" either way round).
     */
    static boolean sameName(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String[] x = a.split(" "), y = b.split(" ");
        if (x.length < 2 || y.length < 2) {
            return false;
        }
        String restX = a.substring(a.indexOf(' ') + 1), restY = b.substring(b.indexOf(' ') + 1);
        boolean initialX = x[0].matches("[a-z]\\.?"), initialY = y[0].matches("[a-z]\\.?");
        return restX.equals(restY) && (initialX || initialY) && x[0].charAt(0) == y[0].charAt(0);
    }

    /**
     * Previous meetings of a saved fixture's two teams (1 call), with the last few results.
     * False if the fixture or a team's GOAL API id isn't known.
     */
    public boolean saveHeadToHead(long fixtureId) {
        Optional<long[]> teams = tables.matchTeams(fixtureId);
        if (teams.isEmpty()) {
            return false;
        }
        Optional<String> a = tables.goalId(teams.get()[0]), b = tables.goalId(teams.get()[1]);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Map<String, Object> body = get("/h2h/" + a.get() + "/" + b.get() + "/direct", Map.of());
        List<Map<String, Object>> past = new ArrayList<>();
        long goals = 0;
        for (Map<String, Object> m : Json.maps(Json.map(body.get("data")).get("matches"))) {
            Long hg = whole(m.get("match_hometeam_score")), ag = whole(m.get("match_awayteam_score"));
            String date = Json.text(m.get("match_date"));
            if (hg == null || ag == null || date == null || !"Finished".equalsIgnoreCase(Json.text(m.get("match_status")))) {
                continue;
            }
            goals += hg + ag;
            String time = Json.text(m.get("match_time"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("utcDate", date + "T" + (time != null && time.matches("\\d{2}:\\d{2}") ? time : "00:00") + ":00Z");
            out.put("status", "FINISHED");
            out.put("competition", Map.of("name", String.valueOf(Json.text(m.get("league_name")))));
            out.put("homeTeam", Map.of("name", String.valueOf(Json.text(m.get("match_hometeam_name")))));
            out.put("awayTeam", Map.of("name", String.valueOf(Json.text(m.get("match_awayteam_name")))));
            out.put("score", Map.of("fullTime", goals(hg, ag)));
            past.add(out);
        }
        past.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("utcDate"))).reversed());
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("numberOfMatches", past.size());
        agg.put("totalGoals", goals);
        tables.saveHeadToHead(fixtureId, Json.write(Map.of("aggregates", agg,
                "matches", past.subList(0, Math.min(H2H_SHOWN, past.size())))));
        return true;
    }

    // ---------------------------------------------------------------- converting GOAL API's answers

    /** {"competition":{...},"matches":[...]} in the shape FootballTables.saveMatches reads. */
    String matchesJson(String code, List<Map<String, Object>> rows, String status) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            Long id = whole(m.get("apiId"));
            Map<String, Object> home = side(m, "home"), away = side(m, "away");
            String utc = utc(m);
            if (id == null || home == null || away == null || utc == null) {
                continue;
            }
            Map<String, Object> score = new LinkedHashMap<>();
            score.put("fullTime", goals(first(m, "homeTeamFtScore", "homeTeamScore"), first(m, "awayTeamFtScore", "awayTeamScore")));
            score.put("halfTime", goals(whole(m.get("homeTeamHalftimeScore")), whole(m.get("awayTeamHalftimeScore"))));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("goalId", Json.text(m.get("id")));   // GOAL API's fixture id (its live calls use it)
            out.put("utcDate", utc);
            out.put("status", status);
            out.put("matchday", whole(m.get("matchRound")));
            out.put("homeTeam", home);
            out.put("awayTeam", away);
            out.put("score", "FINISHED".equals(status) ? score : Map.of());
            matches.add(out);
        }
        return Json.write(Map.of("competition", competition(code), "matches", matches));
    }

    /** One side of a fixture as a team: our id (from its GOAL API id, else its badge), names and badge. */
    private Map<String, Object> side(Map<String, Object> m, String side) {
        String badge = Json.text(m.get("home".equals(side) ? "teamHomeBadge" : "teamAwayBadge"));
        String name = Json.text(m.get(side + "TeamName"));
        Long id = tables.teamIdForGoal(Json.text(m.get(side + "TeamId"))).orElseGet(() -> teamId(null, badge));
        return id == null || name == null ? null : team(id, name, badge, null);
    }

    /** A team as the tables save it: the full name from the badge ("Sheffield Wednesday"), GOAL API's as short name. */
    static Map<String, Object> team(long id, String name, String badge, String country) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("name", badge == null ? name : fullName(badge, name));
        t.put("shortName", name);
        t.put("crest", badge);
        if (country != null) {
            t.put("area", Map.of("name", country));
        }
        return t;
    }

    /** GOAL API's numeric team id, or the number in its badge's file name ("3085_fulham.jpg" -> 3085). */
    static Long teamId(Object apiId, String badge) {
        Long id = whole(apiId);
        if (id != null || badge == null) {
            return id;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d+)_[^/]*$").matcher(badge);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /** "https://.../3099_sheffield-wednesday.jpg" -> "Sheffield Wednesday" (or the given name if there is no slug). */
    static String fullName(String badge, String name) {
        String file = badge.substring(badge.lastIndexOf('/') + 1).replaceFirst("\\.[A-Za-z]+$", "");
        String slug = file.replaceFirst("^\\d+_", "");
        if (slug.isBlank() || slug.equals(file)) {
            return name;
        }
        StringBuilder out = new StringBuilder();
        for (String w : slug.split("-")) {
            if (!w.isEmpty()) {
                out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.length() == 0 ? name : out.toString();
    }

    /** Kick-off as "2026-11-23T20:00:00Z". */
    private static String utc(Map<String, Object> m) {
        String k = Json.text(m.get("kickoffUtc"));
        try {
            if (k != null) {
                return Instant.parse(k).toString();
            }
            String date = Json.text(m.get("matchDate")), time = Json.text(m.get("matchTime"));
            return date == null ? null : date + "T" + (time != null && time.matches("\\d{2}:\\d{2}") ? time : "00:00") + ":00Z";
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> competition(String code) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", Competitions.NAMES.getOrDefault(code, code));
        c.put("type", Competitions.type(code));
        return c;
    }

    private static Map<String, Object> goals(Long home, Long away) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("home", home);
        g.put("away", away);
        return g;
    }

    private static Long first(Map<String, Object> m, String key, String fallback) {
        Long v = whole(m.get(key));
        return v != null ? v : whole(m.get(fallback));
    }

    /** A whole number from GOAL API's text ("4"), or null. */
    static Long whole(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = Json.text(o);
        if (s == null) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String league(String code) {
        String id = Competitions.GOAL_LEAGUES.get(code);
        if (id == null) {
            throw new IllegalArgumentException("No GOAL API league for " + code);
        }
        return id;
    }

    private Map<String, Object> get(String path, Map<String, String> params) {
        Map<String, Object> body = api.getJson(path, params);
        store.saveUsage("goal-api", api.usage());
        return body;
    }

    private static List<Map<String, Object>> data(Map<String, Object> body) {
        return Json.maps(body.get("data"));
    }
}
