package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Answers saved into tables (in football-data.org's shapes, which GoalStats builds) and read back as the pages use them. */
class FootballTablesTest {

    private static final String ARSENAL = "{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\",\"tla\":\"ARS\",\"crest\":\"a.png\"}";
    private static final String SPURS = "{\"id\":73,\"name\":\"Tottenham Hotspur FC\",\"shortName\":\"Tottenham\",\"tla\":\"TOT\",\"crest\":\"t.png\"}";

    @TempDir
    Path tmp;
    Store store;
    FootballTables tables;

    @BeforeEach
    void open() {
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static String result(long id, String status, String date, String home, String away, Integer hg, Integer ag) {
        return "{\"id\":" + id + ",\"status\":\"" + status + "\",\"utcDate\":\"" + date + "\",\"stage\":\"REGULAR_SEASON\","
                + "\"matchday\":3,\"homeTeam\":" + home + ",\"awayTeam\":" + away + ",\"referees\":[{\"name\":\"X\"}],"
                + "\"score\":{\"fullTime\":{\"home\":" + hg + ",\"away\":" + ag + "},\"halfTime\":{\"home\":0,\"away\":0}}}";
    }

    /** Our id for the game GOAL API numbers apiId (games have ids of their own). */
    private long gameId(long apiId) {
        return new Games(store).game(Games.GOAL_NUMBER, String.valueOf(apiId)).orElseThrow();
    }

    @Test
    void aResultCanBecomeAFixtureAndBackAndIsStoredOnce() {
        String comp = "{\"competition\":{\"name\":\"Premier League\",\"type\":\"LEAGUE\"},\"matches\":[";
        tables.saveMatches("PL", "SCHEDULED", comp + result(1, "TIMED", "2026-11-22T16:30:00Z", ARSENAL, SPURS, null, null) + "]}");
        assertThat(tables.matchesJson("PL", "SCHEDULED").orElseThrow()).contains("\"id\":" + gameId(1));
        assertThat(tables.matchesJson("PL", "FINISHED")).isEmpty();   // never fetched

        tables.saveMatches("PL", "FINISHED", comp + result(1, "FINISHED", "2026-11-22T16:30:00Z", ARSENAL, SPURS, 2, 1) + "]}");
        assertThat(tables.matchesJson("PL", "SCHEDULED").orElseThrow()).isEqualTo("{\"matches\":[]}");
        String done = tables.matchesJson("PL", "FINISHED").orElseThrow();
        assertThat(done).contains("\"fullTime\":{\"home\":2,\"away\":1}").contains("\"matchday\":3").doesNotContain("referees");
        assertThat(store.query("SELECT COUNT(*) FROM games", rs -> rs.getLong(1))).containsExactly(1L);
        assertThat(tables.teamResults(73).orElseThrow()).contains("\"id\":" + gameId(1));
    }

    @Test
    void crestsAndCountriesComeFromTheTeamsList() {
        tables.saveTeams("PL", "{\"teams\":[" + ARSENAL.replace("}", ",\"area\":{\"name\":\"England\"}}") + ","
                + "{\"id\":5,\"name\":\"FC Bayern München\",\"area\":{\"name\":\"Germany\"}},{\"name\":\"no id\"}]}");
        assertThat(tables.countries("PL")).containsExactlyInAnyOrder("England", "Germany");
        assertThat(tables.teamsJson("PL").orElseThrow()).startsWith("{\"teams\":[{\"id\":57,\"name\":\"Arsenal FC\"")
                .contains("\"name\":\"FC Bayern München\"");
        assertThat(tables.teamsJson("CL")).isEmpty();

        // A results answer without a country or crest doesn't wipe what the teams list gave
        tables.saveMatches("PL", "FINISHED", "{\"matches\":[" + result(2, "FINISHED", "2026-10-01T15:00:00Z",
                "{\"id\":57,\"name\":\"Arsenal FC\"}", SPURS, 1, 1) + "]}");
        assertThat(tables.teamsJson("PL").orElseThrow()).contains("\"crest\":\"a.png\"").contains("\"area\":{\"name\":\"England\"}");
        assertThat(tables.teamsPlayingIn("PL")).extracting(t -> t.get("name")).containsExactly("Arsenal FC", "Tottenham Hotspur FC");
    }

    @Test
    void scorersAreReplacedOnEachRefreshAndListedMostGoalsFirst() {
        tables.saveScorers("PL", "{\"competition\":{\"name\":\"Premier League\"},\"scorers\":["
                + "{\"player\":{\"id\":1,\"name\":\"Saka\"},\"team\":" + ARSENAL + ",\"goals\":4,\"assists\":null},"
                + "{\"player\":{\"id\":2,\"name\":\"Haaland\",\"position\":\"Attacker\"},\"team\":{\"id\":65,\"name\":\"Man City\"},\"goals\":9},"
                + "{\"player\":{\"id\":4,\"name\":\"Rice\",\"position\":null,\"section\":\"Central Midfield\"},\"team\":" + ARSENAL + ",\"goals\":1},"
                + "{\"player\":{\"name\":\"no id\"}}]}");
        String json = tables.scorersJson("PL").orElseThrow();
        assertThat(json.indexOf("Haaland")).isLessThan(json.indexOf("Saka"));
        assertThat(json).contains("\"competition\":{\"code\":\"PL\",\"name\":\"Premier League\"}").contains("\"assists\":null")
                .contains("\"name\":\"Haaland\",\"position\":\"Attacker\"").contains("\"name\":\"Rice\",\"position\":\"Central Midfield\"").contains("\"name\":\"Saka\",\"position\":null");

        tables.saveScorers("PL", "{\"scorers\":[{\"player\":{\"id\":3,\"name\":\"Isak\"},\"team\":{\"id\":64},\"goals\":2}]}");
        assertThat(tables.scorersJson("PL").orElseThrow()).contains("Isak").doesNotContain("Haaland");
        assertThat(tables.scorersJson("CL")).isEmpty();
    }


    @Test
    void headToHeadKeepsTheTotalsAndLastMeetingsApartFromThisSeasonsMatches() {
        assertThat(tables.headToHeadSavedAt(9)).isEmpty();
        assertThat(tables.headToHeadJson(9)).isEmpty();
        tables.saveHeadToHead(9, "{\"aggregates\":{\"numberOfMatches\":2,\"totalGoals\":5},\"matches\":["
                + result(100, "FINISHED", "2025-03-01T15:00:00Z", ARSENAL, SPURS, 2, 2) + ","
                + result(101, "FINISHED", "2024-09-15T15:00:00Z", SPURS, ARSENAL, 0, 1) + "]}");

        String json = tables.headToHeadJson(9).orElseThrow();
        assertThat(json).startsWith("{\"aggregates\":{\"numberOfMatches\":2,\"totalGoals\":5}")
                .contains("{\"utcDate\":\"2025-03-01T15:00:00Z\",\"status\":\"FINISHED\",\"competition\":{\"name\":null},"
                        + "\"homeTeam\":{\"name\":\"Arsenal\"},\"awayTeam\":{\"name\":\"Tottenham\"},\"score\":{\"fullTime\":{\"home\":2,\"away\":2}}}");
        assertThat(tables.headToHeadSavedAt(9)).isPresent();
        assertThat(tables.teamResults(57)).isEmpty();   // old meetings don't count towards current form

        tables.saveHeadToHead(9, "{\"aggregates\":{\"numberOfMatches\":0}}");   // refreshed: replaced
        assertThat(tables.headToHeadJson(9).orElseThrow()).contains("\"matches\":[]");
    }

    @Test
    void leagueTableIncludesGroupGamesButNotKnockouts() {
        tables.saveMatches("CL", "FINISHED", "{\"matches\":["
                + result(1, "FINISHED", "2026-09-17T19:00:00Z", ARSENAL, SPURS, 1, 0).replace("REGULAR_SEASON", "LEAGUE_STAGE") + ","
                + result(2, "FINISHED", "2027-03-10T19:00:00Z", SPURS, ARSENAL, 4, 0).replace("REGULAR_SEASON", "LAST_16") + "]}");
        String table = tables.standingsJson("CL").orElseThrow();
        assertThat(table).contains("\"position\":1,\"team\":{\"id\":57,\"name\":\"Arsenal FC\",\"shortName\":\"Arsenal\"},"
                + "\"playedGames\":1,\"won\":1,\"draw\":0,\"lost\":0,\"points\":3,\"goalsFor\":1,\"goalsAgainst\":0,\"goalDifference\":1}");
        assertThat(tables.standingsJson("BL1")).isEmpty();
    }
}
