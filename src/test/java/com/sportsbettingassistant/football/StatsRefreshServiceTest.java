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

import com.sportsbettingassistant.odds.OddsApiClient;
import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Refresh Stats (which calls it makes), league tables worked out from results, and a team's results. */
class StatsRefreshServiceTest {

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
        TeamLogoService logos = new TeamLogoService("", api.url(), tables);
        FootballDataClient client = new FootballDataClient("k", api.url(), 10);
        return new StatsRefreshService(client, odds, logos, tables, store, leagueScorers, env);
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

    /** A finished match as football-data.org sends it. */
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

    private static String teams(String... countries) {
        StringBuilder b = new StringBuilder("{\"teams\":[");
        for (int i = 0; i < countries.length; i++) {
            b.append(i > 0 ? "," : "").append("{\"id\":").append(500 + i).append(",\"name\":\"Club ").append(i)
                    .append("\",\"area\":{\"name\":\"").append(countries[i]).append("\"}}");
        }
        return b.append("]}").toString();
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
        tables.saveTeams("PL", teams("England", "Germany", "Narnia"));
        api.answer("/competitions/PL/matches", 200, "{\"matches\":[]}");
        api.answer("/competitions/PL/scorers", 200, "{\"scorers\":[]}");
        api.answer("/competitions/BL1/matches", 200, "{\"matches\":[]}");
        api.answer("/competitions/BL1/scorers", 403, "{\"message\":\"paid\"}");

        assertThat(stats.status().finishedAt).isNull();
        assertThat(stats.start()).isTrue();
        long end = System.currentTimeMillis() + 10_000;
        while (stats.status().running && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }

        StatsRefreshService.Status s = stats.status();
        assertThat(s.running).isFalse();
        assertThat(s.total).isEqualTo(5);   // PL: results, scorers, fixtures; BL1: results, scorers
        assertThat(s.done).isEqualTo(5);
        assertThat(s.step).isEqualTo("Done");
        assertThat(s.errors).containsExactly("/competitions/BL1/scorers: HTTP 403");
        assertThat(api.count("/competitions/PL/matches")).isEqualTo(2);
        assertThat(stats.lastRefresh()).isPresent();
        assertThat(store.refreshedAt("stats")).isPresent();
    }

    @Test
    void competitionsWithoutFixturesAreSkipped() throws Exception {
        assertThat(stats.start()).isTrue();
        long end = System.currentTimeMillis() + 5_000;
        while (stats.status().running && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
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
        tables.saveTeams("PL", teams());
        api.answer("/competitions/PL/matches", 200, "{}");
        api.answer("/competitions/PL/scorers", 200, "{}");
        assertThat(stats.start()).isTrue();
        assertThat(stats.start()).isFalse();
        waitUntilDone(stats);
        assertThat(stats.status().done).isEqualTo(3);
    }

    @Test
    void withoutLeagueScorersEachExtraLeagueIsOneCall() throws Exception {
        StatsRefreshService fewer = service(false);
        premierLeagueHasFixtures();
        tables.saveTeams("PL", teams("Spain", "Spain"));
        api.answer("/competitions/PL/matches", 200, "{}");
        api.answer("/competitions/PL/scorers", 200, "{}");
        api.answer("/competitions/PD/matches", 200, "{}");
        fewer.start();
        waitUntilDone(fewer);
        assertThat(fewer.status().total).isEqualTo(4);   // PL x 3 + La Liga results only (listed once)
        assertThat(api.count("/competitions/PD/scorers")).isZero();
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
        tables.saveTeams("PL", teams());
        String soon = java.time.Instant.now().plus(java.time.Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String later = java.time.Instant.now().plus(java.time.Duration.ofDays(40)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String fixtures = "{\"matches\":[" + match(1, 2, null, null, "REGULAR_SEASON", soon).replace("FINISHED", "TIMED") + ","
                + match(3, 4, null, null, "REGULAR_SEASON", later).replace("FINISHED", "TIMED") + "]}";
        api.answer("/competitions/PL/matches", 200, fixtures);
        api.answer("/competitions/PL/scorers", 200, "{}");
        api.answer("/teams/1", 200, "{\"squad\":[{\"id\":10,\"name\":\"Keeper One\",\"position\":\"Goalkeeper\"},"
                + "{\"id\":11,\"name\":\"Striker One\",\"position\":null,\"section\":\"Offence\"},{\"name\":\"no id\"}]}");
        api.answer("/teams/2", 200, "{\"squad\":[]}");

        stats.start();
        waitUntilDone(stats);
        assertThat(stats.status().total).isEqualTo(5);   // PL x 3 + the squads of teams 1 and 2 (3 and 4 play in 40 days)
        assertThat(api.count("/teams/3")).isZero();
        assertThat(tables.squadJson(1).orElseThrow()).contains("\"name\":\"Keeper One\",\"position\":\"Goalkeeper\"")
                .contains("\"name\":\"Striker One\",\"position\":\"Offence\"").doesNotContain("no id");
        assertThat(tables.squadJson(2)).contains("{\"squad\":[]}");
        assertThat(tables.squadJson(3)).isEmpty();

        stats.start();   // a second refresh within the month: squads aren't fetched again
        waitUntilDone(stats);
        assertThat(api.count("/teams/1")).isEqualTo(1);
    }
}
