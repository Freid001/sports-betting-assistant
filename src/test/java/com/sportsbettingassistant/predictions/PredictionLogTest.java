package com.sportsbettingassistant.predictions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class PredictionLogTest {

    @TempDir
    Path tmp;
    Store store;
    PredictionLog log;
    static final Instant KO = Instant.parse("2026-11-28T15:00:00Z");

    @BeforeEach
    void setUp() {
        store = TempStore.in(tmp);
        log = new PredictionLog(store);
    }

    @AfterEach
    void stop() {
        store.close();
    }

    private static Map<String, Object> prediction(String point, double home) {
        Map<String, Object> p = new HashMap<>();
        p.put("matchId", "e1");
        p.put("point", point);
        p.put("kickoff", KO.toString());
        p.put("home", "Forest");
        p.put("away", "Chelsea");
        p.put("competition", "epl");
        p.put("homeTeamId", 1);
        p.put("awayTeamId", 2);
        p.put("chances", Map.of("home", home));
        return p;
    }

    @Test
    void preMatchCanBeUpdatedUntilKickOffAndBetWindowsAreKeptAsFirstSent() {
        assertThat(log.save(prediction("PRE", 0.40), KO.minusSeconds(7200))).isTrue();
        assertThat(log.save(prediction("PRE", 0.42), KO.minusSeconds(60))).isTrue();    // the last before kick-off counts
        assertThat(log.save(prediction("PRE", 0.99), KO.plusSeconds(60))).isFalse();    // too late
        assertThat(log.save(prediction("60", 0.55), KO.plusSeconds(3900))).isTrue();
        assertThat(log.save(prediction("60", 0.10), KO.plusSeconds(3960))).isFalse();   // never changed

        List<Map<String, Object>> all = log.all();
        assertThat(all).hasSize(2);
        assertThat(String.valueOf(all)).contains("home=0.42").contains("home=0.55").doesNotContain("0.99").doesNotContain("0.1}");
        assertThat(all).allSatisfy(p -> assertThat(p.get("result")).isNull());   // not played yet
    }

    @Test
    void theResultComesFromItsGameOnceTheGameIsFinished() {
        // Refresh Stats saved the fixture first (GOAL API's kick-off format differs a little)
        store.update("INSERT INTO games (id, competition, kickoff_at, home_team_id, away_team_id, status) "
                + "VALUES (9, 'PL', '2026-11-28T15:00:00.000Z', 1, 2, 'SCHEDULED')");
        log.save(prediction("PRE", 0.40), KO.minusSeconds(60));
        assertThat(store.first("SELECT game_id FROM game_sources WHERE source = 'odds' AND source_id = 'e1'", rs -> rs.getLong(1)))
                .contains(9L);                                                 // the same game: same teams, same kick-off
        assertThat(log.all().get(0).get("result")).isNull();                  // not played yet

        store.update("UPDATE games SET status = 'FINISHED', home_goals = 2, away_goals = 1, ht_home = 1, ht_away = 0 WHERE id = 9");
        Map<String, Object> p = log.all().get(0);
        assertThat(String.valueOf(p.get("result"))).contains("home=2").contains("away=1").contains("htHome=1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPredictionIsKeptAsRowsAndComesBackAsItWasSent() {
        store.update("INSERT INTO teams (id, name) VALUES (1, 'Nottingham Forest'), (2, 'Chelsea')");
        store.update("INSERT INTO team_names (source, name, team_id) VALUES ('odds', 'Nottingham Forest', 1), ('odds', 'Chelsea', 2)");
        Map<String, Object> pre = prediction("PRE", 0.40);
        pre.put("version", "2026-11-27");
        pre.put("confidence", "Low");
        pre.put("odds", Map.of("home", 2.5, "draw", 3.4, "away", 2.9));
        pre.put("recommended", List.of(Map.of("key", "h2h", "name", "Chelsea", "price", 2.9, "chance", 0.38)));
        pre.put("predictionRows", List.of(List.of("FT Score", "Forest 1 – 1 Chelsea"), List.of("Result", "Draw (30%)")));
        log.save(pre, KO.minusSeconds(7200));
        Map<String, Object> again = prediction("PRE", 0.41);                  // the Bets page: no Prediction card lines
        again.put("recommended", List.of());
        log.save(again, KO.minusSeconds(60));

        Map<String, Object> window = prediction("60", 0.55);
        window.put("minute", 60);
        window.put("score", List.of(1, 0));
        window.put("recommended", List.of(Map.of("label", "★ Forest · Match Result", "o", Map.of("price", 1.4), "chance", 0.83,
                "fits", true, "hint", "a hint", "r", Map.of("ours", 0.83, "fair", 0.71, "ret", 0.16,
                        "verdict", Map.of("text", "Possible Value", "cls", "verdict-possible")))));
        log.save(window, KO.plusSeconds(3900));

        List<Map<String, Object>> all = log.all();
        Map<String, Object> p = all.stream().filter(x -> "PRE".equals(x.get("point"))).findFirst().orElseThrow();
        assertThat(p).containsEntry("matchId", "e1").containsEntry("home", "Nottingham Forest").containsEntry("competition", "epl")
                .containsEntry("kickoff", KO.toString()).containsEntry("recommended", List.of());
        assertThat((Map<String, Object>) p.get("chances")).containsEntry("home", 0.41);
        assertThat((List<List<String>>) p.get("predictionRows")).containsExactly(               // kept from the first save
                List.of("FT Score", "Forest 1 – 1 Chelsea"), List.of("Result", "Draw (30%)"));

        Map<String, Object> w = all.stream().filter(x -> "60".equals(x.get("point"))).findFirst().orElseThrow();
        assertThat(w).containsEntry("minute", 60L).containsEntry("score", List.of(1L, 0L));
        Map<String, Object> bet = ((List<Map<String, Object>>) w.get("recommended")).get(0);
        assertThat(bet).containsEntry("label", "★ Forest · Match Result").containsEntry("o", Map.of("price", 1.4))
                .containsEntry("fits", true).containsEntry("hint", "a hint");
        assertThat(String.valueOf(bet.get("r"))).contains("ret=0.16").contains("text=Possible Value").contains("cls=verdict-possible");
        assertThat(store.query("SELECT kind FROM prediction_numbers WHERE point = 'PRE' ORDER BY kind", rs -> rs.getString(1)))
                .containsExactly("chances");                                   // the second PRE replaced the first's numbers
    }

    @Test
    void aGameWhoseTeamsArentKnownIsntKept() {
        Map<String, Object> p = prediction("PRE", 0.4);
        p.remove("homeTeamId");
        assertThat(log.save(p, KO.minusSeconds(60))).isFalse();
        assertThat(log.all()).isEmpty();
    }

    @Test
    void aPredictionNeedsAMatchAPointAndAKickOff() {
        Map<String, Object> bad = prediction("FT", 0.4);
        assertThatThrownBy(() -> log.save(bad, KO)).isInstanceOf(IllegalArgumentException.class);
    }
}
