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

    /** A free-plan head-to-head answer: the totals and the last meetings. */
    private static final String H2H = "{\"aggregates\":{\"numberOfMatches\":6,\"totalGoals\":29,\"homeTeam\":{\"id\":65}},"
            + "\"matches\":[{\"utcDate\":\"2026-02-11T20:00:00Z\",\"status\":\"FINISHED\",\"competition\":{\"name\":\"Premier League\"},"
            + "\"homeTeam\":{\"name\":\"Manchester City FC\",\"shortName\":\"Man City\"},\"awayTeam\":{\"name\":\"Fulham FC\"},"
            + "\"score\":{\"fullTime\":{\"home\":3,\"away\":1}}}]}";

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        MockEnvironment env = new MockEnvironment();
        FootballDataClient client = new FootballDataClient("k", api.url(), 10);
        OddsCacheService odds = new OddsCacheService(new OddsApiClient("k", api.url()), store, env);
        StatsRefreshService refresh = new StatsRefreshService(client, odds, new TeamLogoService("", api.url(), tables),
                tables, store, true, env);
        controller = new FootballStatsController(client, refresh, tables);
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
        // football-data sends confirmed-time fixtures as TIMED; they still count as scheduled
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
        assertThat(controller.teamMatches(57).getBody()).contains("\"id\":8")
                .contains("\"score\":{\"fullTime\":{\"home\":1,\"away\":2},\"halfTime\":{\"home\":0,\"away\":1}}");
        assertThat(controller.standings("PL").getBody()).contains("\"position\":1");
        assertThat(api.requests).isEmpty();
    }

    @Test
    void headToHeadIsFetchedOnceThenKeptForAWeek() {
        api.answer("/matches/123/head2head", 200, H2H);
        String first = controller.headToHead(123).getBody();
        assertThat(first).contains("\"numberOfMatches\":6").contains("\"totalGoals\":29").contains("\"home\":3,\"away\":1");
        assertThat(controller.headToHead(123).getBody()).isEqualTo(first);
        assertThat(api.count("/matches/123/head2head")).isEqualTo(1);

        store.update("UPDATE head_to_head SET fetched_at = ?", java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        controller.headToHead(123);                                   // a week later: fetched again
        assertThat(api.count("/matches/123/head2head")).isEqualTo(2);
    }

    @Test
    void anOldHeadToHeadIsUsedIfTheApiRefuses() {
        api.answer("/matches/5/head2head", 200, H2H);
        api.answer("/matches/5/head2head", 429, "{\"message\":\"slow down\"}", java.util.Map.of("X-RequestCounter-Reset", "1"));
        controller.headToHead(5);
        store.update("UPDATE head_to_head SET fetched_at = ?", java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        assertThat(controller.headToHead(5).getStatusCode().value()).isEqualTo(200);

        api.answer("/matches/6/head2head", 404, "{\"message\":\"no such match\"}");
        assertThat(controller.headToHead(6).getStatusCode().value()).isEqualTo(404);   // nothing saved: the API's answer
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
