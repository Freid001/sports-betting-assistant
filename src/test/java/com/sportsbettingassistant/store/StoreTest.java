package com.sportsbettingassistant.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one database file: the tables, the SQL helpers, refresh times, calls left, and housekeeping. */
class StoreTest {

    @TempDir
    Path tmp;

    private Store open() {
        return new Store(tmp.resolve("data/test.db").toString());
    }

    @Test
    void allTablesAreCreatedInANewFile() {
        try (Store s = open()) {
            assertThat(s.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", rs -> rs.getString(1)))
                    .contains("teams", "matches", "scorers", "head_to_head", "odds_events", "odds_prices", "live_games",
                            "favourites", "refreshes", "api_usage")
                    .doesNotContain("saved");
            assertThat(s.file()).isEqualTo(tmp.resolve("data/test.db"));
        }
        assertThat(tmp.resolve("data/test.db")).exists();
    }

    @Test
    void anOlderDatabaseGetsTheNewColumns() throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("data"));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("data/test.db"));
             java.sql.Statement st = c.createStatement()) {
            // scorers as it was before "position" was added
            st.execute("CREATE TABLE scorers (competition TEXT NOT NULL, player_id INTEGER NOT NULL, player TEXT, team_id INTEGER, "
                    + "goals INTEGER, assists INTEGER, penalties INTEGER, games INTEGER, PRIMARY KEY (competition, player_id))");
        }
        try (Store s = open()) {
            assertThat(s.query("SELECT name FROM pragma_table_info('scorers')", rs -> rs.getString(1))).contains("position");
        }
        try (Store s = open()) {   // opening again: the column is already there, no error
            assertThat(s.query("SELECT name FROM pragma_table_info('scorers')", rs -> rs.getString(1))).contains("position");
        }
    }

    @Test
    void sqlHelpersReadWriteAndRollBackOnFailure() {
        try (Store s = open()) {
            s.update("INSERT INTO teams (id, name, short_name) VALUES (?, ?, ?)", 57, "Arsenal FC", null);
            assertThat(s.query("SELECT name FROM teams WHERE id = ?", rs -> rs.getString(1), 57)).containsExactly("Arsenal FC");
            assertThat(s.first("SELECT short_name FROM teams WHERE id = ?", rs -> rs.getString(1), 57)).isEmpty();   // null value
            assertThat(s.first("SELECT name FROM teams WHERE id = ?", rs -> rs.getString(1), 1)).isEmpty();          // no row

            assertThatThrownBy(() -> s.transaction(() -> {
                s.update("INSERT INTO teams (id, name) VALUES (?, ?)", 61, "Chelsea FC");
                s.transaction(() -> s.update("INSERT INTO teams (id, name) VALUES (?, ?)", 65, "Man City"));   // nested
                throw new IllegalStateException("stop");
            })).hasMessage("stop");
            assertThat(s.query("SELECT id FROM teams ORDER BY id", rs -> rs.getLong(1))).containsExactly(57L);   // all rolled back

            s.transaction(() -> s.update("INSERT INTO teams (id, name) VALUES (?, ?)", 61, "Chelsea FC"));
            assertThat(s.query("SELECT id FROM teams ORDER BY id", rs -> rs.getLong(1))).containsExactly(57L, 61L);
            assertThatThrownBy(() -> s.update("INSERT INTO nowhere VALUES (1)")).hasMessageContaining("Database error");
        }
    }

    @Test
    void trueFalseAndTimesAreStoredAsNumbers() {
        try (Store s = open()) {
            Instant when = Instant.parse("2026-11-19T12:00:00Z");
            s.update("INSERT INTO live_games (id, live, updated_at, ticked) VALUES (?, ?, ?, ?)", "g1", true, null, null);
            s.update("INSERT INTO refreshes (name, finished_at) VALUES (?, ?)", "x", when);
            assertThat(s.first("SELECT live FROM live_games", rs -> rs.getInt(1))).contains(1);
            assertThat(s.first("SELECT finished_at FROM refreshes WHERE name = 'x'", rs -> rs.getLong(1))).contains(when.toEpochMilli());
            assertThat(s.first("SELECT ticked FROM live_games", rs -> Store.longOrNull(rs, "ticked"))).isEmpty();
            assertThat(s.first("SELECT ticked FROM live_games", rs -> Store.doubleOrNull(rs, "ticked"))).isEmpty();
        }
    }

    @Test
    void refreshTimesAndCallsLeftSurviveARestart() {
        Instant when = Instant.parse("2026-11-19T12:00:00Z");
        try (Store s = open()) {
            assertThat(s.refreshedAt("stats")).isEmpty();
            s.markRefreshed("stats", when);
            s.markRefreshed("stats", when.plusSeconds(60));
            s.saveUsage("goal-api", Map.of("limit", "1000", "remaining", "973", "reset", "1795132800", "checkedAt", "now"));
        }
        try (Store s = open()) {
            assertThat(s.refreshedAt("stats")).contains(when.plusSeconds(60));
            assertThat(s.usage("goal-api").orElseThrow()).containsEntry("remaining", "973").containsEntry("limit", "1000");
            assertThat(s.usage("odds-api")).isEmpty();
        }
    }

    @Test
    void anOlderDatabaseLosesItsUnusedKeyValueTable() {
        try (Store s = open()) {
            s.update("CREATE TABLE saved (key TEXT PRIMARY KEY, value TEXT, saved_at INTEGER)");
            s.markRefreshed("old-files-imported", Instant.now());
        }
        try (Store s = open()) {
            assertThat(s.query("SELECT name FROM sqlite_master WHERE name = 'saved'", rs -> rs.getString(1))).isEmpty();
            assertThat(s.refreshedAt("old-files-imported")).isEmpty();
        }
    }

    @Test
    void unusedSpaceIsGivenBackWhenMostOfTheFileIsEmpty() {
        try (Store s = open()) {
            String big = "x".repeat(100_000);
            for (int i = 0; i < 20; i++) {
                s.update("INSERT INTO teams (id, name) VALUES (?, ?)", i, big);
            }
            s.update("DELETE FROM teams");
            long before = s.first("PRAGMA freelist_count", rs -> rs.getLong(1)).orElseThrow();
            s.compact();
            assertThat(before).isGreaterThan(0);
            assertThat(s.first("PRAGMA freelist_count", rs -> rs.getLong(1)).orElseThrow()).isZero();
            s.compact();   // nothing to do the second time
        }
    }

    @Test
    void aClosedDatabaseSaysWhatWentWrong() {
        Store s = open();
        s.close();
        assertThatThrownBy(() -> s.refreshedAt("x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Database error");
        assertThatThrownBy(() -> s.update("DELETE FROM teams")).isInstanceOf(IllegalStateException.class);
    }
}
