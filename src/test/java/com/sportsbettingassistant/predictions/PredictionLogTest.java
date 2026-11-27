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
    void theResultComesFromTheSavedResultsOnceTheGameIsFinished() {
        log.save(prediction("PRE", 0.40), KO.minusSeconds(60));
        store.update("INSERT INTO matches (id, competition, utc_date, status, home_team_id, away_team_id, home_goals, away_goals, "
                + "ht_home, ht_away) VALUES (9, 'PL', ?, 'FINISHED', 1, 2, 2, 1, 1, 0)", KO.toString());

        Map<String, Object> p = log.all().get(0);
        assertThat(String.valueOf(p.get("result"))).contains("home=2").contains("away=1").contains("htHome=1");
    }

    @Test
    void aGameFollowedLiveGivesItsResultBeforeRefreshStatsDoes() {
        log.save(prediction("PRE", 0.40), KO.minusSeconds(60));
        // GOAL API's names ("Nottingham") and kick-off format (.000Z) differ from The Odds API's
        store.update("INSERT INTO live_games (id, kickoff, home, away, home_score, away_score, status) "
                + "VALUES ('g1', '2026-11-28T15:00:00.000Z', 'Nottingham Forest', 'Chelsea', '3', '2', 'FINISHED')");
        store.update("INSERT INTO live_snapshots (game_id, period, home_score, away_score, stats) VALUES ('g1', 'HT', '1', '0', '{}')");

        Map<String, Object> p = log.all().get(0);
        assertThat(String.valueOf(p.get("result"))).contains("home=3").contains("away=2").contains("htHome=1").contains("htAway=0");

        store.update("UPDATE live_games SET status = 'LIVE'");   // not finished: no result yet
        assertThat(log.all().get(0).get("result")).isNull();
    }

    @Test
    void aPredictionNeedsAMatchAPointAndAKickOff() {
        Map<String, Object> bad = prediction("FT", 0.4);
        assertThatThrownBy(() -> log.save(bad, KO)).isInstanceOf(IllegalArgumentException.class);
    }
}
