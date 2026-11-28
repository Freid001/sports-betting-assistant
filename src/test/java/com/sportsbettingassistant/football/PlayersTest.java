package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** One identity per player: GOAL API's squad id and its scorer id for him point at one player. */
class PlayersTest {

    @TempDir
    Path tmp;
    Store store;
    FootballTables tables;
    Players players;

    @BeforeEach
    void setUp() {
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        players = new Players(store);
        store.update("INSERT INTO teams (id, name) VALUES (61, 'Chelsea'), (58, 'Aston Villa')");
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aScorerAndHisSquadEntryAreOnePlayerThoughGoalApiNumbersThemDifferently() {
        tables.saveScorers("PL", "{\"scorers\":[{\"player\":{\"id\":2465174313,\"name\":\"Cole Palmer\"},\"team\":{\"id\":61},\"goals\":8},"
                + "{\"player\":{\"id\":77,\"name\":\"N. Jackson\"},\"team\":{\"id\":61},\"goals\":5}]}");
        tables.saveSquad(61, "{\"squad\":[{\"id\":3973598339,\"name\":\"Nicolas Jackson\",\"position\":\"Forwards\","
                + "\"stats\":{\"matchPlayed\":\"12\",\"goals\":\"5\",\"shotsTotal\":\"30\",\"rating\":\"7.25\",\"number\":\"15\","
                + "\"injured\":\"No\",\"isCaptain\":\"false\",\"age\":\"24\"}}]}");

        long jackson = players.player(Players.GOAL_SCORER, "77").orElseThrow();
        assertThat(players.player(Players.GOAL, "3973598339")).contains(jackson);              // "N. Jackson" = "Nicolas Jackson"
        assertThat(store.query("SELECT COUNT(*) FROM players", rs -> rs.getInt(1))).containsExactly(2);
        String squad = tables.squadJson(61).orElseThrow();
        assertThat(squad).contains("\"id\":" + jackson + ",\"name\":\"Nicolas Jackson\",\"position\":\"Forwards\"")
                .contains("\"number\":\"15\"").contains("\"age\":\"24\"").contains("\"isCaptain\":\"false\"").contains("\"injured\":\"No\"")
                .contains("\"matchPlayed\":\"12\"").contains("\"rating\":\"7.25\"")
                .contains("\"games\":12,\"goals\":5,\"shots\":30");
        assertThat(store.query("SELECT stat FROM player_season_stats ORDER BY stat", rs -> rs.getString(1)))
                .containsExactly("goals", "matchPlayed", "rating", "shotsTotal");               // numbers only, one row each
        assertThat(tables.scorersJson("PL").orElseThrow()).contains("\"player\":{\"id\":" + jackson + ",\"name\":\"Nicolas Jackson\"");
    }

    @Test
    void aScorerMissingFromHisSquadIsLookedUpOnceAndThenBelongsToIt() {
        tables.saveScorers("PL", "{\"scorers\":[{\"player\":{\"id\":5,\"name\":\"Morgan Rogers\"},\"team\":{\"id\":58},\"goals\":5}]}");
        tables.saveSquad(58, "{\"squad\":[{\"id\":100,\"name\":\"Ollie Watkins\"}]}");
        assertThat(tables.scorersMissingFromSquads(java.util.List.of(58L))).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("name", "Morgan Rogers").containsEntry("teamName", "Aston Villa"));

        // found under England: who he is, not England's numbers (GoalStats.findPlayer keeps only these)
        tables.addToSquad(58, "Morgan Rogers", Map.of("id", 900, "position", "Midfielders",
                "stats", Map.of("filedUnder", "England", "age", "23")));
        assertThat(tables.scorersMissingFromSquads(java.util.List.of(58L))).isEmpty();
        long rogers = players.player(Players.GOAL_SCORER, "5").orElseThrow();
        assertThat(players.player(Players.GOAL, "900")).contains(rogers);
        assertThat(tables.squadJson(58).orElseThrow()).contains("\"id\":" + rogers + ",\"name\":\"Morgan Rogers\",\"position\":\"Midfielders\"")
                .contains("\"filedUnder\":\"England\"");

        tables.saveSquad(58, "{\"squad\":[{\"id\":100,\"name\":\"Ollie Watkins\"}]}");   // the squad again: he stays
        assertThat(tables.squadJson(58).orElseThrow()).contains("Morgan Rogers");
    }
}
