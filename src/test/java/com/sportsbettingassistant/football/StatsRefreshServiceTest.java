package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.mock.env.MockEnvironment;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.odds.OddsApiClient;
import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Refresh Stats (which GOAL API calls it makes), league tables worked out from results, and a team's results. */
class StatsRefreshServiceTest {

    static final String PL = "/leagues/" + Competitions.GOAL_LEAGUES.get("PL");
    static final String BL1 = "/leagues/" + Competitions.GOAL_LEAGUES.get("BL1");
    static final String PD = "/leagues/" + Competitions.GOAL_LEAGUES.get("PD");
    static final String EMPTY = "{\"data\":[]}";

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    FootballTables tables;
    OddsCacheService odds;
    StatsRefreshService stats;

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        odds = new OddsCacheService(new OddsApiClient("k", api.url()),
                store, new MockEnvironment().withProperty("football.tournaments[0]", "epl"));
        stats = service(true);
    }

    private StatsRefreshService service(boolean leagueScorers) {
        MockEnvironment env = new MockEnvironment().withProperty("football.tournaments[0]", "epl");
        GoalStats goal = new GoalStats(new GoalApiClient("k", api.url()), tables, store);
        return new StatsRefreshService(goal, odds, tables, store, leagueScorers, env);
    }

    private void waitUntilDone(StatsRefreshService s) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (s.status().running && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    private static int nextId = 1000;

    /** A finished match in the tables' shape. */
    private static String match(long home, long away, Integer hg, Integer ag, String stage, String date) {
        String score = hg == null ? "{\"home\":null,\"away\":null}" : "{\"home\":" + hg + ",\"away\":" + ag + "}";
        return "{\"id\":" + (nextId++) + ",\"status\":\"FINISHED\",\"utcDate\":\"" + date + "\",\"stage\":\"" + stage + "\","
                + "\"homeTeam\":{\"id\":" + home + ",\"name\":\"T" + home + "\",\"shortName\":\"T" + home + "\"},"
                + "\"awayTeam\":{\"id\":" + away + ",\"name\":\"T" + away + "\",\"shortName\":\"T" + away + "\"},"
                + "\"score\":{\"fullTime\":" + score + "}}";
    }

    /** Odds saved for the Premier League with one fixture, so Refresh Stats treats it as active. */
    private void premierLeagueHasFixtures() {
        odds.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h", "skybet", "2",
                "[{\"id\":\"e1\",\"commence_time\":\"2026-11-21T15:00:00Z\",\"home_team\":\"A\",\"away_team\":\"B\"}]");
    }

    /** GOAL API's team list: team i is "g{i}", id i, from the given country. */
    private static String goalTeams(String... countries) {
        StringBuilder b = new StringBuilder("{\"data\":[");
        for (int i = 0; i < countries.length; i++) {
            b.append(i > 0 ? "," : "").append("{\"id\":\"g").append(i + 1).append("\",\"apiId\":\"").append(i + 1)
                    .append("\",\"name\":\"Club ").append(i + 1).append("\",\"badge\":\"https://b/").append(i + 1)
                    .append("_club.jpg\",\"country\":\"").append(countries[i]).append("\"}");
        }
        return b.append("]}").toString();
    }

    /** Every Premier League call answered (teams as given, the rest empty). */
    private void premierLeagueAnswers(String teams) {
        api.answer(PL + "/teams", 200, teams);
        api.answer(PL + "/results", 200, EMPTY);
        api.answer(PL + "/fixtures", 200, EMPTY);
        api.answer(PL + "/standings", 200, EMPTY);
        api.answer(PL + "/top-scorers", 200, EMPTY);
    }

    private void savedResults() {
        tables.saveMatches("PL", "FINISHED", "{\"competition\":{\"name\":\"Premier League\",\"type\":\"LEAGUE\"},\"matches\":["
                + match(1, 2, 2, 0, "REGULAR_SEASON", "2026-08-16T14:00:00Z") + ","
                + match(2, 3, 1, 1, "REGULAR_SEASON", "2026-08-23T14:00:00Z") + ","
                + match(3, 1, 3, 1, "REGULAR_SEASON", "2026-08-30T14:00:00Z") + ","
                + match(1, 3, 5, 0, "QUARTER_FINALS", "2026-09-06T14:00:00Z") + ","   // knockout: not in the table
                + match(2, 1, null, null, "REGULAR_SEASON", "2026-09-13T14:00:00Z") + "]}");   // no score yet
    }

    @Test
    @SuppressWarnings("unchecked")
    void leagueTableIsWorkedOutFromResults() throws Exception {
        savedResults();
        Map<String, Object> out = new BasicJsonParser().parseMap(tables.standingsJson("PL").orElseThrow());
        Map<String, Object> total = (Map<String, Object>) ((List<Object>) out.get("standings")).get(0);
        List<Map<String, Object>> table = (List<Map<String, Object>>) total.get("table");

        assertThat(table).extracting(r -> ((Map<String, Object>) r.get("team")).get("name")).containsExactly("T3", "T1", "T2");
        assertThat(table.get(0)).containsEntry("points", 4L).containsEntry("won", 1L).containsEntry("draw", 1L)
                .containsEntry("goalsFor", 4L).containsEntry("goalDifference", 2L).containsEntry("position", 1L);
        assertThat(table.get(2)).containsEntry("lost", 1L).containsEntry("playedGames", 2L);
        assertThat(((Map<String, Object>) out.get("competition")).get("name")).isEqualTo("Premier League");
    }

    @Test
    void noSavedResultsMeansNoTable() {
        assertThat(tables.standingsJson("BL1")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTeamsResultsComeNewestFirst() throws Exception {
        savedResults();
        String json = tables.teamResults(1).orElseThrow();
        List<Map<String, Object>> matches = (List<Map<String, Object>>) new BasicJsonParser().parseMap(json).get("matches");
        assertThat(matches).extracting(m -> m.get("utcDate"))
                .containsExactly("2026-09-13T14:00:00Z", "2026-09-06T14:00:00Z", "2026-08-30T14:00:00Z", "2026-08-16T14:00:00Z");
        assertThat(tables.teamResults(99)).isEmpty();
    }

    @Test
    void refreshFetchesTournamentAndNeededLeagueDataThenRecordsWhen() throws Exception {
        // Premier League has fixtures; one of its teams is German, so the Bundesliga is needed too
        premierLeagueHasFixtures();
        premierLeagueAnswers(goalTeams("England", "Germany", "Narnia"));
        api.answer(BL1 + "/results", 200, EMPTY);
        api.answer(BL1 + "/standings", 200, EMPTY);
        api.answer(BL1 + "/top-scorers", 403, "{\"message\":\"paid\"}");

        assertThat(stats.status().finishedAt).isNull();
        assertThat(stats.start()).isTrue();
        waitUntilDone(stats);

        StatsRefreshService.Status s = stats.status();
        assertThat(s.running).isFalse();
        assertThat(s.total).isEqualTo(8);   // PL: teams, results, fixtures, table, scorers; BL1: results, table, scorers
        assertThat(s.done).isEqualTo(8);
        assertThat(s.step).isEqualTo("Done");
        assertThat(s.errors).hasSize(1);
        assertThat(s.errors.get(0)).startsWith("Top scorers in BL1: ").contains("403");
        assertThat(api.count(PL + "/results")).isEqualTo(1);
        assertThat(stats.lastRefresh()).isPresent();
        assertThat(store.refreshedAt("stats")).isPresent();
    }

    @Test
    void competitionsWithoutFixturesAreSkipped() throws Exception {
        assertThat(stats.start()).isTrue();
        waitUntilDone(stats);
        assertThat(stats.status().total).isZero();
        assertThat(api.requests).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelOnPointsIsDecidedByGoalDifferenceThenGoalsScored() throws Exception {
        tables.saveMatches("PL", "FINISHED", "{\"matches\":["
                + match(1, 9, 1, 0, "REGULAR_SEASON", "2026-08-16T14:00:00Z") + ","    // T1: 3 pts, +1, 1 scored
                + match(2, 9, 3, 2, "REGULAR_SEASON", "2026-08-16T14:00:00Z") + ","    // T2: 3 pts, +1, 3 scored
                + match(3, 9, 4, 0, "REGULAR_SEASON", "2026-08-16T14:00:00Z") + "]}"); // T3: 3 pts, +4
        Map<String, Object> out = new BasicJsonParser().parseMap(tables.standingsJson("PL").orElseThrow());
        List<Map<String, Object>> table = (List<Map<String, Object>>)
                ((Map<String, Object>) ((List<Object>) out.get("standings")).get(0)).get("table");
        assertThat(table).extracting(r -> ((Map<String, Object>) r.get("team")).get("name"))
                .startsWith("T3", "T2", "T1");
    }

    @Test
    void aTeamsResultsAreTheSameEachTimeUntilNewOnesAreSaved() throws Exception {
        savedResults();
        String first = tables.teamResults(1).orElseThrow();
        assertThat(tables.teamResults(1)).contains(first);
    }

    @Test
    void clickingRefreshStatsTwiceDoesNotStartASecondRun() throws Exception {
        premierLeagueHasFixtures();
        premierLeagueAnswers(EMPTY);
        assertThat(stats.start()).isTrue();
        assertThat(stats.start()).isFalse();
        waitUntilDone(stats);
        assertThat(stats.status().done).isEqualTo(5);
    }

    @Test
    void withoutLeagueScorersEachExtraLeagueIsTwoCalls() throws Exception {
        StatsRefreshService fewer = service(false);
        premierLeagueHasFixtures();
        premierLeagueAnswers(goalTeams("Spain", "Spain"));
        api.answer(PD + "/results", 200, EMPTY);
        api.answer(PD + "/standings", 200, EMPTY);
        fewer.start();
        waitUntilDone(fewer);
        assertThat(fewer.status().total).isEqualTo(7);   // PL x 5 + La Liga results and table (listed once)
        assertThat(api.count(PD + "/top-scorers")).isZero();
    }

    @Test
    void damagedAnswersAreTreatedAsEmpty() throws Exception {
        tables.saveMatches("PL", "FINISHED", "{not json");
        assertThat(tables.teamResults(1)).isEmpty();
        assertThat(tables.standingsJson("PL")).isPresent();   // an empty table rather than an error
    }

    @Test
    void squadsAreFetchedForTeamsPlayingSoonAndKeptForAMonth() throws Exception {
        premierLeagueHasFixtures();
        api.answer(PL + "/teams", 200, goalTeams("England", "England", "England", "England"));
        api.answer(PL + "/results", 200, EMPTY);
        api.answer(PL + "/standings", 200, EMPTY);
        api.answer(PL + "/top-scorers", 200, EMPTY);
        String soon = java.time.Instant.now().plus(java.time.Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String later = java.time.Instant.now().plus(java.time.Duration.ofDays(40)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        api.answer(PL + "/fixtures", 200, "{\"data\":["
                + GoalStatsTest.game(900, "2026/2027", "SCHEDULED", "Club 1", 1, "Club 2", 2, soon, null, null) + ","
                + GoalStatsTest.game(901, "2026/2027", "SCHEDULED", "Club 3", 3, "Club 4", 4, later, null, null) + "]}");
        api.answer("/teams/g1/players", 200, "{\"data\":[{\"apiId\":\"10\",\"name\":\"Keeper One\",\"type\":\"Goalkeepers\"},"
                + "{\"apiId\":\"11\",\"name\":\"Striker One\",\"type\":\"Forwards\"},{\"name\":\"no id\"}]}");
        api.answer("/teams/g2/players", 200, EMPTY);

        stats.start();
        waitUntilDone(stats);
        assertThat(stats.status().errors).isEmpty();
        assertThat(stats.status().total).isEqualTo(7);   // PL x 5 + the squads of teams 1 and 2 (3 and 4 play in 40 days)
        assertThat(api.count("/teams/g3/players")).isZero();
        assertThat(tables.squadJson(1).orElseThrow()).contains("\"name\":\"Keeper One\",\"position\":\"Goalkeepers\"")
                .contains("\"name\":\"Striker One\",\"position\":\"Forwards\"").doesNotContain("no id");
        assertThat(tables.squadJson(2)).contains("{\"squad\":[]}");
        assertThat(tables.squadJson(3)).isEmpty();

        stats.start();   // a second refresh within the month: squads aren't fetched again
        waitUntilDone(stats);
        assertThat(api.count("/teams/g1/players")).isEqualTo(1);
    }
}
