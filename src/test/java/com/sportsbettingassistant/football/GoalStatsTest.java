package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.support.TempStore;

/** GOAL API's answers turned into the tables' shapes: teams, results, fixtures, tables, scorers, squads, head to head. */
class GoalStatsTest {

    static final String PL = "/leagues/" + Competitions.GOAL_LEAGUES.get("PL");

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    FootballTables tables;
    GoalStats goal;

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        goal = new GoalStats(new GoalApiClient("k", api.url()), tables, store);
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    static String team(String goalId, long apiId, String name, String slug, String country) {
        return "{\"id\":\"" + goalId + "\",\"apiId\":\"" + apiId + "\",\"name\":\"" + name + "\",\"badge\":\"https://b/" + apiId
                + "_" + slug + ".jpg\",\"country\":\"" + country + "\",\"venueName\":\"Somewhere\"}";
    }

    /** A GOAL API fixture or result. */
    static String game(long id, String year, String status, String home, long homeId, String away, long awayId,
                       String kickoff, String ft, String ht) {
        String[] f = ft == null ? new String[] {null, null} : ft.split("-"), h = ht == null ? new String[] {null, null} : ht.split("-");
        return "{\"apiId\":\"" + id + "\",\"leagueYear\":\"" + year + "\",\"matchStatus\":\"" + status + "\",\"matchRound\":\"7\","
                + "\"kickoffUtc\":\"" + kickoff + "\",\"homeTeamId\":\"g" + homeId + "\",\"awayTeamId\":\"g" + awayId + "\","
                + "\"homeTeamName\":\"" + home + "\",\"awayTeamName\":\"" + away + "\","
                + "\"teamHomeBadge\":\"https://b/" + homeId + "_" + slug(home) + ".jpg\",\"teamAwayBadge\":\"https://b/" + awayId + "_" + slug(away) + ".jpg\","
                + "\"homeTeamFtScore\":" + q(f[0]) + ",\"awayTeamFtScore\":" + q(f[1]) + ","
                + "\"homeTeamHalftimeScore\":" + q(h[0]) + ",\"awayTeamHalftimeScore\":" + q(h[1]) + "}";
    }

    /** "Club 1" -> "club-1", as in GOAL API's badge file names. */
    private static String slug(String name) {
        return name.toLowerCase().replace(' ', '-');
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private void plTeams() {
        api.answer(PL + "/teams", 200, "{\"data\":[" + team("g3086", 3086, "Brentford", "brentford", "England") + ","
                + team("g3073", 3073, "Everton", "everton", "England") + ","
                + team("g102", 102, "Manchester Utd", "manchester-united", "England") + "]}");
        goal.saveTeams("PL");
    }

    @Test
    void teamsGetGoalApisNumericIdsFullNamesFromTheirBadgesAndCountries() {
        plTeams();
        String json = tables.teamsJson("PL").orElseThrow();
        assertThat(json).contains("\"id\":102,\"name\":\"Manchester United\",\"shortName\":\"Manchester Utd\"")
                .contains("\"crest\":\"https://b/3086_brentford.jpg\"").contains("\"area\":{\"name\":\"England\"}")
                .doesNotContain("Somewhere");
        assertThat(tables.goalId(3086)).contains("g3086");
        assertThat(tables.teamIdForGoal("g102")).contains(102L);
        assertThat(tables.countries("PL")).containsExactly("England");
    }

    @Test
    void resultsAreThisSeasonsOnlyWithFullAndHalfTimeScores() {
        plTeams();
        api.answer(PL + "/results", 200, "{\"data\":["
                + game(812752, "2026/2027", "FINISHED", "Brentford", 3086, "Everton", 3073, "2026-11-23T20:00:00.000Z", "4-0", "2-0") + ","
                + game(812700, "2026/2027", "POSTPONED", "Everton", 3073, "Brentford", 3086, "2026-11-01T15:00:00.000Z", null, null) + ","
                + game(619746, "2025/2026", "FINISHED", "Everton", 3073, "Brentford", 3086, "2026-03-05T20:00:00.000Z", "1-3", "1-3")
                + "],\"pagination\":{\"hasMore\":true}}");

        assertThat(goal.saveResults("PL")).isEqualTo(1);   // last season reached: no second page
        String json = tables.teamResults(3086).orElseThrow();
        assertThat(json).contains("\"id\":812752").contains("\"utcDate\":\"2026-11-23T20:00:00Z\"")
                .contains("\"score\":{\"fullTime\":{\"home\":4,\"away\":0},\"halfTime\":{\"home\":2,\"away\":0}}")
                .contains("\"homeTeam\":{\"id\":3086,\"name\":\"Brentford\"")
                .contains("\"competition\":{\"code\":\"PL\",\"name\":\"Premier League\",\"type\":\"LEAGUE\"}")
                .doesNotContain("619746").doesNotContain("812700");
        assertThat(api.last().query()).contains("limit=100").contains("offset=0");
    }

    @Test
    void aLongSeasonIsReadAPageAtATime() {
        StringBuilder page = new StringBuilder("{\"data\":[");
        for (int i = 0; i < GoalStats.PAGE; i++) {
            page.append(i > 0 ? "," : "").append(game(1000 + i, "2026/2027", "FINISHED", "A", 1, "B", 2, "2026-11-01T15:00:00Z", "1-0", "0-0"));
        }
        api.answer(PL + "/results", 200, page.append("],\"pagination\":{\"hasMore\":true}}").toString());
        api.answer(PL + "/results", 200, "{\"data\":[" + game(5, "2026/2027", "FINISHED", "A", 1, "B", 2, "2026-08-21T19:00:00Z", "0-2", "0-1")
                + "],\"pagination\":{\"hasMore\":false}}");
        assertThat(goal.saveResults("PL")).isEqualTo(2);
        assertThat(tables.teamResults(1).orElseThrow()).contains("\"id\":5,").contains("\"id\":1099,");
        assertThat(api.last().query()).contains("offset=100");
    }

    @Test
    void fixturesAreSavedAsScheduledAndUnknownTeamsGetTheirBadgesNumber() {
        api.answer(PL + "/fixtures", 200, "{\"data\":["
                + game(900, "2026/2027", "SCHEDULED", "Doncaster", 3082, "Huddersfield", 3101, "2026-11-28T15:00:00.000Z", null, null) + "]}");
        goal.saveFixtures("PL");
        assertThat(tables.matchesJson("PL", "SCHEDULED").orElseThrow()).contains("\"id\":900").contains("\"status\":\"SCHEDULED\"")
                .contains("\"homeTeam\":{\"id\":3082,\"name\":\"Doncaster\"");
        assertThat(tables.matchTeams(900).orElseThrow()).containsExactly(3082, 3101);
        assertThat(api.last().query()).contains("from=").contains("to=");
    }

    @Test
    void theLeagueTableIsGoalApisOwn() {
        plTeams();
        api.answer(PL + "/standings", 200, "{\"data\":[{\"teamId\":\"g3086\",\"teamName\":\"Brentford\",\"overallLeaguePosition\":\"6\","
                + "\"overallLeaguePlayed\":\"11\",\"overallLeagueW\":\"5\",\"overallLeagueD\":\"2\",\"overallLeagueL\":\"4\","
                + "\"overallLeagueGF\":\"20\",\"overallLeagueGA\":\"11\",\"overallLeaguePTS\":\"17\"},"
                + "{\"teamId\":\"unknown\",\"teamName\":\"Nobody FC\",\"overallLeaguePosition\":\"20\"}]}");
        goal.saveStandings("PL");
        String json = tables.standingsJson("PL").orElseThrow();
        assertThat(json).contains("\"position\":6,\"team\":{\"id\":3086,\"name\":\"Brentford\",\"shortName\":\"Brentford\"}")
                .contains("\"playedGames\":11").contains("\"points\":17").contains("\"goalDifference\":9")
                .doesNotContain("Nobody");
    }

    @Test
    void topScorersAreMatchedToTheirTeamByName() {
        plTeams();
        api.answer(PL + "/top-scorers", 200, "{\"data\":[{\"playerKey\":\"659972248\",\"playerName\":\"Bryan Mbeumo\","
                + "\"teamName\":\"Manchester Utd\",\"goals\":\"9\",\"assists\":\"2\",\"penaltyGoals\":\"1\"},"
                + "{\"playerKey\":\"1\",\"playerName\":\"Someone\",\"teamName\":\"Elsewhere\",\"goals\":\"3\"}]}");
        goal.saveScorers("PL");
        assertThat(tables.scorersJson("PL").orElseThrow())
                .contains("\"player\":{\"id\":659972248,\"name\":\"Bryan Mbeumo\"")
                .contains("\"team\":{\"id\":102,\"name\":\"Manchester United\",\"shortName\":\"Manchester Utd\"}")
                .contains("\"goals\":9").contains("\"assists\":2").contains("\"penalties\":1")
                .contains("\"name\":\"Someone\"");
    }

    @Test
    void squadsNeedTheTeamsGoalApiId() {
        plTeams();
        api.answer("/teams/g3086/players", 200, "{\"data\":[{\"apiId\":\"3975994970\",\"name\":\"Mark Flekken\",\"type\":\"Goalkeepers\"},"
                + "{\"name\":\"no id\"}]}");
        assertThat(goal.saveSquad(3086)).isTrue();
        assertThat(tables.squadJson(3086).orElseThrow()).contains("\"id\":3975994970,\"name\":\"Mark Flekken\",\"position\":\"Goalkeepers\"")
                .doesNotContain("no id");
        assertThat(goal.saveSquad(999)).isFalse();
        assertThat(api.count("/teams/g3086/players")).isEqualTo(1);
    }

    @Test
    void headToHeadKeepsFinishedMeetingsNewestFirst() {
        plTeams();
        api.answer(PL + "/fixtures", 200, "{\"data\":["
                + game(77, "2026/2027", "SCHEDULED", "Brentford", 3086, "Everton", 3073, "2026-11-29T15:00:00Z", null, null) + "]}");
        goal.saveFixtures("PL");
        api.answer("/h2h/g3086/g3073/direct", 200, "{\"data\":{\"matches\":["
                + "{\"match_date\":\"2025-09-20\",\"match_time\":\"15:00\",\"match_status\":\"Finished\",\"league_name\":\"Premier League\","
                + "\"match_hometeam_name\":\"Everton\",\"match_awayteam_name\":\"Brentford\",\"match_hometeam_score\":\"1\",\"match_awayteam_score\":\"1\"},"
                + "{\"match_date\":\"2026-04-18\",\"match_time\":\"13:30\",\"match_status\":\"Finished\",\"league_name\":\"Premier League\","
                + "\"match_hometeam_name\":\"Brentford\",\"match_awayteam_name\":\"Everton\",\"match_hometeam_score\":\"3\",\"match_awayteam_score\":\"0\"},"
                + "{\"match_date\":\"2026-11-29\",\"match_status\":\"\",\"match_hometeam_score\":\"\",\"match_awayteam_score\":\"\"}]}}");

        assertThat(goal.saveHeadToHead(77)).isTrue();
        String json = tables.headToHeadJson(77).orElseThrow();
        assertThat(json).contains("\"numberOfMatches\":2").contains("\"totalGoals\":5");
        assertThat(json.indexOf("2026-04-18T13:30:00Z")).isLessThan(json.indexOf("2025-09-20T15:00:00Z"));
        assertThat(goal.saveHeadToHead(12345)).isFalse();   // not a saved fixture: no call
        assertThat(api.count("/h2h/g3086/g3073/direct")).isEqualTo(1);
    }

    @Test
    void idsAndNamesComeFromTheBadgeWhenNeeded() {
        assertThat(GoalStats.teamId(null, "https://media.goal-api.com/badges/3085_fulham.jpg")).isEqualTo(3085L);
        assertThat(GoalStats.teamId("164", null)).isEqualTo(164L);
        assertThat(GoalStats.teamId(null, "https://b/logo.png")).isNull();
        assertThat(GoalStats.fullName("https://b/3099_sheffield-wednesday.jpg", "Sheffield Wed")).isEqualTo("Sheffield Wednesday");
        assertThat(GoalStats.fullName("https://b/3099.jpg", "Sheffield Wed")).isEqualTo("Sheffield Wed");
        assertThat(GoalStats.whole("4")).isEqualTo(4L);
        assertThat(GoalStats.whole("")).isNull();
    }
}
