package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.support.TempStore;

class TeamLogoServiceTest {

    private static final String PL_TEAMS = "{\"competition\":{\"name\":\"Premier League\",\"type\":\"LEAGUE\"},\"teams\":["
            + "{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\",\"tla\":\"ARS\",\"crest\":\"https://crests/57.png\","
            + "\"area\":{\"name\":\"England\"},\"venue\":\"Emirates Stadium\"}]}";

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    FootballTables tables;

    @BeforeEach
    void start() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    @Test
    void crestsAreFetchedOnceThenReadFromTheTables() {
        api.answer("/competitions/PL/teams", 200, PL_TEAMS);
        TeamLogoService logos = new TeamLogoService("k", api.url(), tables);

        String json = logos.teams("epl", false).orElseThrow();
        assertThat(json).contains("\"name\":\"Arsenal FC\"").contains("\"shortName\":\"Arsenal\"")
                .contains("\"crest\":\"https://crests/57.png\"").contains("\"area\":{\"name\":\"England\"}")
                .doesNotContain("Emirates");   // only what the pages use is kept
        assertThat(logos.teams("epl", false)).contains(json);
        assertThat(api.count("/competitions/PL/teams")).isEqualTo(1);

        logos.teams("epl", true);   // refresh asks again
        assertThat(api.count("/competitions/PL/teams")).isEqualTo(2);
        assertThat(api.last().header("X-Auth-Token")).isEqualTo("k");
    }

    @Test
    void competitionsWithoutCrestsGiveNothing() {
        assertThat(new TeamLogoService("k", api.url(), tables).teams("uefa_nations_league", false)).isEmpty();
        assertThat(api.requests).isEmpty();
    }

    @Test
    void withoutAKeyOnlyWhatIsSavedIsUsed() {
        TeamLogoService logos = new TeamLogoService("", api.url(), tables);
        assertThat(logos.hasKey()).isFalse();
        assertThat(logos.teams("uefa_champs_league", false)).isEmpty();
        tables.saveTeams("CL", "{\"teams\":[]}");
        assertThat(logos.teams("uefa_champs_league", true)).contains("{\"teams\":[]}");
        assertThat(api.requests).isEmpty();
    }
}
