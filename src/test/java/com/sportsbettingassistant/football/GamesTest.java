package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** One identity per game: each API's id for it, and each API's spelling of its teams, found once and kept. */
class GamesTest {

    @TempDir
    Path tmp;
    Store store;
    Games games;

    @BeforeEach
    void setUp() {
        store = TempStore.in(tmp);
        games = new Games(store);
        Object[][] teams = {{1, "Nottingham Forest", "Nottingham", "NOT"}, {2, "Chelsea", "Chelsea", "CHE"},
                {3, "Manchester United", "Man Utd", "MUN"}, {4, "Manchester City", "Man City", "MCI"},
                {5, "Paris Saint-Germain", "PSG", "PSG"}};
        for (Object[] t : teams) {
            store.update("INSERT INTO teams (id, name, short_name, tla) VALUES (?, ?, ?, ?)", t);
            store.update("INSERT INTO competition_teams (competition, team_id) VALUES ('PL', ?)", t[0]);
        }
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void eachApisSpellingOfATeamIsMatchedOnceAndKept() {
        assertThat(games.team("odds", "PL", "Nottingham Forest")).contains(1L);
        assertThat(games.team("goal", "PL", "Nottingham")).contains(1L);
        assertThat(games.team("odds", "PL", "Manchester United")).contains(3L);   // not City
        assertThat(games.team("odds", "PL", "Paris Saint Germain")).contains(5L);
        assertThat(games.team("odds", "PL", "Arsenal")).isEmpty();                 // no such team: not guessed
        assertThat(games.team("odds", "PL", "Manchester")).isEmpty();              // United or City? not guessed
        assertThat(store.query("SELECT name FROM team_names WHERE source = 'odds' ORDER BY name", rs -> rs.getString(1)))
                .containsExactly("Manchester United", "Nottingham Forest", "Paris Saint Germain");

        store.update("UPDATE team_names SET team_id = 2 WHERE name = 'Nottingham Forest'");   // fixed by hand: kept
        assertThat(games.team("odds", "PL", "Nottingham Forest")).contains(2L);
    }

    @Test
    void aGameIsFoundByItsTeamsAndKickOffOnceThenByEachApisId() {
        long game = games.link(Games.GOAL_NUMBER, "812700", "PL", "2026-11-27T20:00:00.000Z", 1L, 2L).orElseThrow();
        // The Odds API's event for it: same teams, a slightly different kick-off format
        assertThat(games.linkByNames(Games.ODDS, "e1", "PL", "2026-11-27T20:00:00Z", "Nottingham Forest", "Chelsea"))
                .contains(game);
        assertThat(games.game(Games.ODDS, "e1")).contains(game);
        assertThat(games.oddsEvent(game)).contains("e1");
        assertThat(games.get(game).orElseThrow()).containsEntry("homeTeamId", 1L).containsEntry("competition", "PL");

        // the return game (teams the other way round) and a game a week later are other games
        long back = games.link(Games.ODDS, "e2", "PL", "2026-11-28T20:00:00Z", 2L, 1L).orElseThrow();
        long later = games.link(Games.ODDS, "e3", "PL", "2026-12-04T20:00:00Z", 1L, 2L).orElseThrow();
        assertThat(back).isNotEqualTo(game);
        assertThat(later).isNotIn(game, back);
        assertThat(store.query("SELECT COUNT(*) FROM games", rs -> rs.getInt(1))).containsExactly(3);
    }

    @Test
    void anApisTwoIdsAreTwoGamesEvenWithTheSameTeams() {
        long a = games.link(Games.GOAL_NUMBER, "1", "PL", "2026-11-27T20:00:00Z", 1L, 2L).orElseThrow();
        long b = games.link(Games.GOAL_NUMBER, "2", "PL", "2026-11-27T20:00:00Z", 1L, 2L).orElseThrow();
        assertThat(a).isNotEqualTo(b);
        assertThat(games.link(Games.GOAL_NUMBER, "1", "PL", "2026-11-27T20:00:00Z", 1L, 2L)).contains(a);   // again: same
    }

    @Test
    void unknownTeamsOrNoKickOffGiveNoGame() {
        assertThat(games.linkByNames(Games.ODDS, "e1", "PL", "2026-11-27T20:00:00Z", "Arsenal", "Chelsea")).isEmpty();
        assertThat(games.link(Games.ODDS, "e1", "PL", "not a time", 1L, 2L)).isEmpty();
        assertThat(store.query("SELECT COUNT(*) FROM games", rs -> rs.getInt(1))).containsExactly(0);
    }

    @Test
    void savedOddsEventsAreLinkedOnce() {
        store.update("INSERT INTO odds_events (id, sport, kickoff_at, home_team, away_team) VALUES "
                + "('e1', 'soccer_epl', '2026-11-27T20:00:00Z', 'Nottingham Forest', 'Chelsea'), "
                + "('e2', 'soccer_epl', '2026-11-28T15:00:00Z', 'Arsenal', 'Chelsea'), "
                + "('e3', 'soccer_bundesliga', '2026-11-28T15:00:00Z', 'Bayern', 'Chelsea')");   // not one of ours
        assertThat(games.linkOddsEvents()).isEqualTo(1);
        assertThat(games.linkOddsEvents()).isZero();
    }
}
