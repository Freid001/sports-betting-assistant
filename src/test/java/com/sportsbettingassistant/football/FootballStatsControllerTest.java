package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.odds.OddsApiClient;
import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** The match page's data: read from the tables, "Not saved yet" when missing, head to head on demand. */
class FootballStatsControllerTest {

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    FootballTables tables;
    FootballStatsController controller;

    /** GOAL API's head to head for g65 v g63: two finished meetings and one not played yet. */
    private static final String H2H = "{\"data\":{\"matches\":["
            + "{\"match_date\":\"2026-02-11\",\"match_time\":\"20:00\",\"match_status\":\"Finished\",\"league_name\":\"Premier League\","
            + "\"match_hometeam_name\":\"Manchester City\",\"match_awayteam_name\":\"Fulham\",\"match_hometeam_score\":\"3\",\"match_awayteam_score\":\"1\"},"
            + "{\"match_date\":\"2025-10-02\",\"match_time\":\"15:00\",\"match_status\":\"Finished\",\"league_name\":\"Premier League\","
            + "\"match_hometeam_name\":\"Fulham\",\"match_awayteam_name\":\"Manchester City\",\"match_hometeam_score\":\"2\",\"match_awayteam_score\":\"2\"},"
            + "{\"match_date\":\"2026-12-01\",\"match_status\":\"\",\"match_hometeam_score\":\"\",\"match_awayteam_score\":\"\"}]}}";
    private static final String H2H_PATH = "/h2h/g65/g63/direct";

    /** A saved fixture (id) between Man City (65) and Fulham (63), whose GOAL API ids are known. */
    /** A saved fixture (GOAL API's apiId), and our id for it. */
    private long fixture(long id) {
        tables.saveMatches("PL", "SCHEDULED", "{\"matches\":[{\"id\":" + id + ",\"status\":\"SCHEDULED\",\"utcDate\":\"2026-12-01T20:00:00Z\","
                + "\"homeTeam\":{\"id\":65,\"name\":\"Manchester City\"},\"awayTeam\":{\"id\":63,\"name\":\"Fulham\"}}]}");
        tables.saveGoalId("g65", 65);
        tables.saveGoalId("g63", 63);
        return new Games(store).game(Games.GOAL_NUMBER, String.valueOf(id)).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        MockEnvironment env = new MockEnvironment();
        GoalStats goal = new GoalStats(new GoalApiClient("k", api.url()), tables, store);
        OddsCacheService odds = new OddsCacheService(new OddsApiClient("k", api.url()), store, env);
        StatsRefreshService refresh = new StatsRefreshService(goal, odds, tables, store, true, env);
        controller = new FootballStatsController(goal, refresh, tables);
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }


    @Test
    void missingDataSaysClickRefreshStats() {
        assertThat(controller.competitionMatches("PL", "SCHEDULED").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.standings("PL").getBody()).contains("\"notSaved\":true");
        assertThat(controller.teamMatches(57).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.teamSquad(57).getStatusCode().value()).isEqualTo(404);   // squad not saved yet
        tables.saveSquad(57, "{\"squad\":[{\"id\":1,\"name\":\"Bukayo Saka\",\"position\":\"Offence\"}]}");
        assertThat(controller.teamSquad(57).getBody()).contains("\"name\":\"Bukayo Saka\",\"position\":\"Offence\"");
        assertThat(controller.scorers("PL").getBody()).isEqualTo("{\"scorers\":[]}");   // optional: empty, not an error
    }

    @Test
    void savedDataIsServedWithoutCalls() {
        String arsenal = "{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\"}";
        String spurs = "{\"id\":73,\"name\":\"Tottenham Hotspur FC\",\"shortName\":\"Tottenham\"}";
        // fixtures with a confirmed time (TIMED) still count as scheduled
        tables.saveMatches("PL", "SCHEDULED", "{\"competition\":{\"name\":\"Premier League\",\"type\":\"LEAGUE\"},"
                + "\"matches\":[{\"id\":9,\"status\":\"TIMED\",\"utcDate\":\"2026-11-22T16:30:00Z\",\"homeTeam\":" + arsenal
                + ",\"awayTeam\":" + spurs + ",\"score\":{\"fullTime\":{\"home\":null,\"away\":null}}}]}");
        tables.saveMatches("PL", "FINISHED", "{\"matches\":[{\"id\":8,\"status\":\"FINISHED\",\"utcDate\":\"2026-11-01T15:00:00Z\","
                + "\"homeTeam\":" + spurs + ",\"awayTeam\":" + arsenal
                + ",\"score\":{\"fullTime\":{\"home\":1,\"away\":2},\"halfTime\":{\"home\":0,\"away\":1}}}]}");
        tables.saveScorers("PL", "{\"scorers\":[{\"player\":{\"id\":1,\"name\":\"Saka\"},\"team\":" + arsenal
                + ",\"goals\":7,\"assists\":3,\"penalties\":1,\"playedMatches\":11}]}");

        String fixtures = controller.competitionMatches("PL", "SCHEDULED").getBody();
        assertThat(fixtures).contains("\"id\":9").contains("\"status\":\"TIMED\"")
                .contains("\"homeTeam\":{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\"")
                .contains("\"competition\":{\"code\":\"PL\",\"name\":\"Premier League\",\"type\":\"LEAGUE\"}");
        assertThat(controller.scorers("PL").getBody()).contains("\"player\":{\"id\":1,\"name\":\"Saka\"}")
                .contains("\"team\":{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\"}")
                .contains("\"goals\":7").contains("\"playedMatches\":11");
        assertThat(controller.teamMatches(57).getBody()).contains("\"id\":" + new Games(store).game(Games.GOAL_NUMBER, "8").orElseThrow())
                .contains("\"score\":{\"fullTime\":{\"home\":1,\"away\":2},\"halfTime\":{\"home\":0,\"away\":1}}");
        assertThat(controller.standings("PL").getBody()).contains("\"position\":1");
        assertThat(api.requests).isEmpty();
    }

    @Test
    void headToHeadIsFetchedOnceThenKeptForAWeek() {
        long game = fixture(123);
        api.answer(H2H_PATH, 200, H2H);
        String first = controller.headToHead(game).getBody();
        assertThat(first).contains("\"numberOfMatches\":2").contains("\"totalGoals\":8").contains("\"home\":3,\"away\":1")
                .contains("\"utcDate\":\"2026-02-11T20:00:00Z\"");
        assertThat(controller.headToHead(game).getBody()).isEqualTo(first);
        assertThat(api.count(H2H_PATH)).isEqualTo(1);

        store.update("UPDATE head_to_head SET fetched_at = ?", java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        controller.headToHead(game);                                  // a week later: fetched again
        assertThat(api.count(H2H_PATH)).isEqualTo(2);
    }

    @Test
    void anOldHeadToHeadIsUsedIfTheApiRefuses() {
        long game = fixture(5);
        api.answer(H2H_PATH, 200, H2H);
        api.answer(H2H_PATH, 429, "{\"message\":\"slow down\"}");
        controller.headToHead(game);
        store.update("UPDATE head_to_head SET fetched_at = ?", java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        assertThat(controller.headToHead(game).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.headToHead(game).getBody()).contains("\"numberOfMatches\":2");

        // nothing saved and the API refuses: an error; a fixture we don't know: no meetings, no call
        store.update("DELETE FROM head_to_head");
        assertThat(controller.headToHead(game).getStatusCode().value()).isEqualTo(503);
        long calls = api.requests.size();
        assertThat(controller.headToHead(6).getBody()).contains("\"numberOfMatches\":0");
        assertThat(api.requests).hasSize((int) calls);
    }

    @Test
    void badCodesAndStatusesAreRefused() {
        assertThatThrownBy(() -> controller.standings("pl;x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.competitionMatches("PL", "LIVE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshStatusIsReported() throws InterruptedException {
        assertThat(controller.refreshStatus().running).isFalse();
        assertThat(controller.startRefresh()).isNotNull();
        long end = System.currentTimeMillis() + 5_000;   // let it finish before the temp folder is removed
        while (controller.refreshStatus().running && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(controller.refreshStatus().step).isEqualTo("Done");
    }
}
