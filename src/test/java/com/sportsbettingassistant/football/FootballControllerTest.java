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
import org.springframework.ui.ExtendedModelMap;

import com.sportsbettingassistant.odds.OddsApiClient;
import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class FootballControllerTest {

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    FootballTables tables;
    OddsCacheService odds;
    FootballController controller;

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        MockEnvironment env = new MockEnvironment()
                .withProperty("football.tournaments[0]", "epl")
                .withProperty("football.tournaments[1]", "fifa_world_cup");
        tables = new FootballTables(store);
        odds = new OddsCacheService(new OddsApiClient("k", api.url()), store, env);
        controller = new FootballController(odds, new TeamLogoService("", api.url(), tables));
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    @Test
    void pagesAndTheMatchIdGoToTheTemplate() {
        assertThat(controller.page()).isEqualTo("football");
        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(controller.match("abc123", model)).isEqualTo("football-match");
        assertThat(model.get("matchId")).isEqualTo("abc123");
        assertThatThrownBy(() -> controller.match("../x", new ExtendedModelMap())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tournamentsSayWhichHaveSavedOdds() throws Exception {
        odds.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h", "skybet", "2", "[]");
        assertThat(controller.tournaments()).extracting(t -> t.get("name") + ":" + t.get("cached"))
                .containsExactly("epl:true", "fifa_world_cup:false");
    }

    @Test
    void crestsComeFromTheSavedListOrAnEmptyOne() throws Exception {
        assertThat(controller.teams("epl", false).getBody()).isEqualTo("{\"teams\":[]}");
        tables.saveTeams("PL", "{\"teams\":[{\"id\":57,\"name\":\"Arsenal FC\",\"crest\":\"c.png\",\"area\":{\"name\":\"England\"}}]}");
        assertThat(controller.teams("epl", false).getBody()).isEqualTo(
                "{\"teams\":[{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":null,\"tla\":null,\"crest\":\"c.png\","
                + "\"area\":{\"name\":\"England\"}}]}");
    }

    @Test
    void refreshRefreshesEveryTournament() {
        api.answer("/sports", 200, "[]");
        assertThat(controller.refresh("h2h", "skybet", 30).getBody()).contains("inactive");
    }

    @Test
    void allMarketsRunsForEachTournamentWithFixtures() {
        odds.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h", "skybet", "2", "[{\"id\":\"e1\",\"commence_time\":\""
                + java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + "\"}]");
        odds.save("soccer_fifa_world_cup", "2026-11-19T12:00:00Z", 30, "h2h", "skybet", "0", "[]");   // no fixtures: skipped
        api.answer("/sports", 200, "[]", java.util.Map.of("x-requests-remaining", "400"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, "{}", java.util.Map.of("x-requests-last", "0"));
        String body = controller.refreshMarkets(30, "skybet", null, null).getBody();
        assertThat(body).startsWith("[{\"sport\":\"soccer_epl\",\"matches\":1,\"credits\":0").doesNotContain("world_cup");
        assertThat(api.last().query()).contains("markets=" + String.join(",", odds.extraMarkets("skybet")));
        controller.refreshMarkets(30, "skybet", "btts, alternate_totals", null);   // just the ticked ones
        assertThat(api.last().query()).contains("markets=btts,alternate_totals&");
        assertThat(controller.refreshMarkets(30, "skybet", "btts", "e9").getBody()).contains("\"matches\":0");   // favourites only
        assertThat(controller.refreshMarkets(30, "skybet", "btts", "").getBody()).contains("\"matches\":0");
    }
}
