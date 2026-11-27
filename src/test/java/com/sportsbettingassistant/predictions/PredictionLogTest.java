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
    void predictionsSavedBeforeGamesExistedAreLinkedOnce() {
        store.update("INSERT INTO predictions (match_id, point, kickoff, competition, home_team_id, away_team_id, body) "
                + "VALUES ('e7', 'PRE', ?, 'epl', 1, 2, '{}'), ('e7', 'HT', ?, 'epl', 1, 2, '{}')", KO.toString(), KO.toString());
        assertThat(log.linkGames()).isEqualTo(1);                                // one game, both points
        assertThat(log.linkGames()).isZero();                                    // done: nothing the next time
        assertThat(store.query("SELECT competition || ' ' || home_team_id || ' ' || away_team_id FROM games", rs -> rs.getString(1)))
                .containsExactly("PL 1 2");
    }

    @Test
    void aPredictionNeedsAMatchAPointAndAKickOff() {
        Map<String, Object> bad = prediction("FT", 0.4);
        assertThatThrownBy(() -> log.save(bad, KO)).isInstanceOf(IllegalArgumentException.class);
    }
}
