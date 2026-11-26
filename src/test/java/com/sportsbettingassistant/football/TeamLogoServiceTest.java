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

class TeamLogoServiceTest {

    private static final String PL_TEAMS = "{\"data\":[{\"id\":\"g3081\",\"apiId\":\"3081\",\"name\":\"Arsenal\","
            + "\"badge\":\"https://b/3081_arsenal.jpg\",\"country\":\"England\",\"venueName\":\"Emirates Stadium\"}]}";
    private static final String PL = "/leagues/" + Competitions.GOAL_LEAGUES.get("PL") + "/teams";

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

    private TeamLogoService logos(String key) {
        return new TeamLogoService(new GoalStats(new GoalApiClient(key, api.url()), tables, store), tables);
    }

    @Test
    void badgesAreFetchedOnceThenReadFromTheTables() {
        api.answer(PL, 200, PL_TEAMS);
        TeamLogoService logos = logos("k");

        String json = logos.teams("epl", false).orElseThrow();
        assertThat(json).contains("\"name\":\"Arsenal\"").contains("\"shortName\":\"Arsenal\"")
                .contains("\"crest\":\"https://b/3081_arsenal.jpg\"").contains("\"area\":{\"name\":\"England\"}")
                .doesNotContain("Emirates");   // only what the pages use is kept
        assertThat(logos.teams("epl", false)).contains(json);
        assertThat(api.count(PL)).isEqualTo(1);

        logos.teams("epl", true);   // refresh asks again
        assertThat(api.count(PL)).isEqualTo(2);
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer k");
    }

    @Test
    void tournamentsWithoutACompetitionUseEveryTeamSaved() {
        TeamLogoService logos = logos("k");
        assertThat(logos.teams("some_other_cup", false)).isEmpty();   // nothing saved yet
        api.answer(PL, 200, PL_TEAMS);
        logos.teams("epl", false);
        assertThat(logos.teams("some_other_cup", false).orElseThrow()).contains("\"name\":\"Arsenal\"").contains("\"all\":true");
        assertThat(api.count(PL)).isEqualTo(1);
    }

    @Test
    void withoutAKeyOnlyWhatIsSavedIsUsed() {
        TeamLogoService logos = logos("");
        assertThat(logos.hasKey()).isFalse();
        assertThat(logos.teams("uefa_champs_league", false)).isEmpty();
        tables.saveTeams("CL", "{\"teams\":[]}");
        assertThat(logos.teams("uefa_champs_league", true)).contains("{\"teams\":[]}");
        assertThat(api.requests).isEmpty();
    }

    @Test
    void aFailedCallIsTriedOnceADayNotOnEveryPageLoad() {
        api.answer(PL, 500, "{\"message\":\"down\"}");
        TeamLogoService logos = logos("k");
        assertThat(logos.teams("epl", false)).isEmpty();
        assertThat(logos.teams("epl", false)).isEmpty();
        assertThat(api.count(PL)).isEqualTo(1);
    }
}
