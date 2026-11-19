package com.sportsbettingassistant.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class OddsCacheControllerTest {

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    OddsCacheController controller;

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        MockEnvironment env = new MockEnvironment().withProperty("football.tournaments[0]", "epl");
        controller = new OddsCacheController(new OddsCacheService(new OddsApiClient("k", api.url()), store, env));
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    @Test
    void quotaReadsTheCreditHeaders() {
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "481", "x-requests-used", "19"));
        assertThat(controller.quota()).containsEntry("remaining", "481").containsEntry("used", "19").containsEntry("ok", true);
    }

    @Test
    void readingSomethingNotSavedSays404() {
        var res = controller.read("soccer_epl");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).contains("Refresh it first");
    }

    @Test
    void refreshThenReadAndList() {
        api.answer("/sports/soccer_epl/odds", 200, "[]");
        assertThat(controller.refresh("soccer_epl", "h2h", "skybet", 30).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.read("soccer_epl").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.list()).containsExactly("soccer_epl");
        assertThat(controller.tournaments()).containsExactly("soccer_epl");
    }

    @Test
    void refreshAllReturnsTheSummary() {
        api.answer("/sports", 200, "[]");
        assertThat(controller.refreshAll("h2h", "skybet", 30).getBody()).contains("inactive");
    }

    @Test
    void badInputIsRefused() {
        assertThatThrownBy(() -> controller.read("../x")).isInstanceOf(IllegalArgumentException.class);   // ApiErrors makes it a 400
    }

    @Test
    void theExtraMarketsAreListedForThePopUp() {
        assertThat(controller.extraMarkets().get("skybet")).contains("btts", "alternate_totals");
        assertThat(controller.extraMarkets().get("betway")).contains("alternate_totals_corners", "alternate_totals_cards");
    }
}
