package com.sportsbettingassistant.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Everything the app saves, in one SQLite file (default data/sports-betting-assistant.db).
 * The tables are listed in {@link #SCHEMA}; each part of the app reads and writes its own tables
 * (FootballTables, OddsCacheService, LiveService, FavouritesController) through the SQL helpers here.
 */
@Component
public class Store implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Store.class);

    /** All tables. Times are milliseconds since 1970 (UTC) unless the column says _iso. */
    /** Columns added after a table was first made: added to older databases (ignored if already there). */
    static final List<String> NEW_COLUMNS = List.of(
            "ALTER TABLE scorers ADD COLUMN position TEXT");   // e.g. "Forwards"

    static final String SCHEMA = """
        -- when something last finished: 'stats' (Refresh Stats), 'live', 'live-list', 'matches/PL/FINISHED'...
        CREATE TABLE IF NOT EXISTS refreshes (name TEXT PRIMARY KEY, finished_at INTEGER NOT NULL);
        -- app choices kept on the server, e.g. the bookmaker picked in Refresh Odds (pages and live odds both use it)
        CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT);
        -- tidy-up of earlier versions: the key -> JSON table and its import marker are no longer used
        DROP TABLE IF EXISTS saved;
        DELETE FROM refreshes WHERE name = 'old-files-imported';
        -- calls left per API, from its last answer
        CREATE TABLE IF NOT EXISTS api_usage (api TEXT PRIMARY KEY, call_limit TEXT, remaining TEXT, resets TEXT, checked_at TEXT);
        CREATE TABLE IF NOT EXISTS favourites (position INTEGER NOT NULL, name TEXT PRIMARY KEY, short_name TEXT,
            tla TEXT, crest TEXT, type TEXT);

        -- football stats from GOAL API (in the shapes football-data.org used, which the pages read)
        CREATE TABLE IF NOT EXISTS competitions (code TEXT PRIMARY KEY, name TEXT, type TEXT);
        CREATE TABLE IF NOT EXISTS teams (id INTEGER PRIMARY KEY, name TEXT NOT NULL, short_name TEXT, tla TEXT,
            crest TEXT, country TEXT);
        CREATE TABLE IF NOT EXISTS competition_teams (competition TEXT NOT NULL, team_id INTEGER NOT NULL,
            PRIMARY KEY (competition, team_id));
        -- each team's squad: every player and his position (GOAL API /teams/{id}/players), for players who
        -- aren't in the scorer tables yet
        CREATE TABLE IF NOT EXISTS squads (team_id INTEGER NOT NULL, player_id INTEGER NOT NULL, name TEXT NOT NULL,
            position TEXT, PRIMARY KEY (team_id, player_id));
        CREATE TABLE IF NOT EXISTS matches (id INTEGER PRIMARY KEY, competition TEXT NOT NULL, utc_date TEXT,
            status TEXT, stage TEXT, grp TEXT, matchday INTEGER, home_team_id INTEGER, away_team_id INTEGER,
            home_goals INTEGER, away_goals INTEGER, ht_home INTEGER, ht_away INTEGER);
        CREATE INDEX IF NOT EXISTS matches_by_home ON matches (home_team_id, utc_date);
        CREATE INDEX IF NOT EXISTS matches_by_away ON matches (away_team_id, utc_date);
        CREATE INDEX IF NOT EXISTS matches_by_competition ON matches (competition, status);
        -- head to head for a fixture: totals, and the last few meetings (kept apart from matches so old
        -- seasons don't count towards a team's form)
        CREATE TABLE IF NOT EXISTS head_to_head (match_id INTEGER PRIMARY KEY, number_of_matches INTEGER,
            total_goals INTEGER, fetched_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS head_to_head_matches (match_id INTEGER NOT NULL, position INTEGER NOT NULL,
            utc_date TEXT, status TEXT, competition TEXT, home TEXT, away TEXT, home_goals INTEGER, away_goals INTEGER,
            PRIMARY KEY (match_id, position));
        CREATE TABLE IF NOT EXISTS scorers (competition TEXT NOT NULL, player_id INTEGER NOT NULL, player TEXT,
            team_id INTEGER, goals INTEGER, assists INTEGER, penalties INTEGER, games INTEGER, position TEXT,
            PRIMARY KEY (competition, player_id));

        -- The Odds API
        CREATE TABLE IF NOT EXISTS odds_fetches (sport TEXT PRIMARY KEY, fetched_at_iso TEXT NOT NULL, days INTEGER,
            markets TEXT, bookmakers TEXT, credits TEXT);
        CREATE TABLE IF NOT EXISTS odds_events (id TEXT PRIMARY KEY, sport TEXT NOT NULL, sport_title TEXT,
            kickoff TEXT, home_team TEXT, away_team TEXT);
        CREATE INDEX IF NOT EXISTS odds_events_by_sport ON odds_events (sport, kickoff);
        -- when a match's extra markets (BTTS, goals lines, corners...) were last fetched from each bookmaker,
        -- which came back, and the cost (replaces odds_extras, which had one row per match)
        DROP TABLE IF EXISTS odds_extras;
        CREATE TABLE IF NOT EXISTS odds_extra_fetches (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL,
            fetched_at_iso TEXT NOT NULL, markets TEXT, credits INTEGER, PRIMARY KEY (event_id, bookmaker));
        -- in-play prices, one fetch per match, bookmaker and point in the match (10, 25, HT, 60, 80), markets is JSON
        -- (replaces odds_live, which kept only the last fetch)
        DROP TABLE IF EXISTS odds_live;
        CREATE TABLE IF NOT EXISTS odds_live_points (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL, point TEXT NOT NULL,
            fetched_at_iso TEXT, credits INTEGER, markets TEXT NOT NULL, PRIMARY KEY (event_id, bookmaker, point));
        CREATE TABLE IF NOT EXISTS odds_prices (event_id TEXT NOT NULL, position INTEGER NOT NULL, bookmaker TEXT,
            bookmaker_title TEXT, market TEXT, outcome TEXT, description TEXT, point REAL, price REAL,
            last_update TEXT, PRIMARY KEY (event_id, position));

        -- GOAL API (Live Stats)
        CREATE TABLE IF NOT EXISTS live_games (id TEXT PRIMARY KEY, tournament TEXT, league_id TEXT, date TEXT,
            kickoff TEXT, home TEXT, away TEXT, home_score TEXT, away_score TEXT, status TEXT, live INTEGER,
            minute TEXT, elapsed TEXT, extra TEXT, period TEXT, updated_at TEXT, ticked INTEGER);
        CREATE TABLE IF NOT EXISTS live_stats (game_id TEXT PRIMARY KEY, xg_home REAL, xg_away REAL, minute TEXT,
            final INTEGER, updated_at TEXT);
        CREATE TABLE IF NOT EXISTS live_stat_rows (game_id TEXT NOT NULL, position INTEGER NOT NULL, label TEXT,
            home TEXT, away TEXT, PRIMARY KEY (game_id, position));
        CREATE TABLE IF NOT EXISTS live_players (game_id TEXT NOT NULL, side TEXT NOT NULL, position INTEGER NOT NULL,
            name TEXT, shots INTEGER, on_target INTEGER, goals INTEGER, PRIMARY KEY (game_id, side, position));
        -- a game's stats at a point in the match ('25', 'HT', '60', '80', 'FT'): stats is the JSON the page shows
        CREATE TABLE IF NOT EXISTS live_snapshots (game_id TEXT NOT NULL, period TEXT NOT NULL, home_score TEXT,
            away_score TEXT, stats TEXT NOT NULL, saved_at TEXT, PRIMARY KEY (game_id, period));
        -- what happened in a followed game (goal, yellow, red, penalty, shot, kickoff, ht, second, ft), worked out
        -- refresh by refresh: the match page's Timeline card
        CREATE TABLE IF NOT EXISTS live_events (game_id TEXT NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL,
            side TEXT, player TEXT, score TEXT, minute TEXT, half INTEGER, elapsed INTEGER, sort_order INTEGER, at TEXT,
            PRIMARY KEY (game_id, seq));
        -- games to follow live once GOAL API lists them (on match day), picked with the match page's play button
        CREATE TABLE IF NOT EXISTS live_follow (tournament TEXT NOT NULL, kickoff TEXT NOT NULL, home TEXT NOT NULL,
            away TEXT NOT NULL, PRIMARY KEY (tournament, kickoff, home));
        DROP TABLE IF EXISTS goal_badges;
        -- GOAL API's own id for each team (squads and head to head are asked for by it)
        CREATE TABLE IF NOT EXISTS goal_teams (goal_id TEXT PRIMARY KEY, team_id INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS goal_teams_by_team ON goal_teams (team_id);
        -- league tables as GOAL API gives them (every game this season, even ones missing from its results)
        CREATE TABLE IF NOT EXISTS standings (competition TEXT NOT NULL, team_id INTEGER NOT NULL, team_name TEXT,
            position INTEGER, played INTEGER, won INTEGER, draw INTEGER, lost INTEGER, goals_for INTEGER,
            goals_against INTEGER, points INTEGER, grp TEXT, PRIMARY KEY (competition, team_id));
        -- once, when stats moved from football-data.org to GOAL API: its saved stats go (team ids differ)
        DELETE FROM competition_teams WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM squads WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM scorers WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM matches WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM head_to_head_matches WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM head_to_head WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM teams WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM competitions WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        DELETE FROM refreshes WHERE (name = 'stats' OR name LIKE 'teams/%' OR name LIKE 'matches/%' OR name LIKE 'scorers/%'
            OR name LIKE 'squad/%') AND NOT EXISTS (SELECT 1 FROM settings WHERE name = 'stats-source');
        INSERT OR IGNORE INTO settings (name, value) VALUES ('stats-source', 'goal-api');
        """;

    private final Path file;
    private final Connection db;
    private int depth;   // nested transaction() calls

    public Store(@Value("${store.file:data/sports-betting-assistant.db}") String file) {
        this.file = Path.of(file);
        try {
            if (this.file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(this.file.toAbsolutePath().getParent());
            }
            db = DriverManager.getConnection("jdbc:sqlite:" + this.file);
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");     // readers don't block the writer
                s.execute("PRAGMA busy_timeout=5000");
                for (String sql : SCHEMA.split(";")) {
                    // skip pieces that are only comments (a ';' inside a comment would otherwise run as SQL)
                    String code = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
                    if (!code.isBlank()) {
                        s.execute(sql);
                    }
                }
                for (String sql : NEW_COLUMNS) {
                    try {
                        s.execute(sql);
                    } catch (SQLException alreadyThere) {
                        // a database made after the column was added already has it
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Couldn't open the database " + file + ": " + e.getMessage(), e);
        }
    }

    /** The database file. */
    public Path file() {
        return file;
    }

    // ---------------------------------------------------------------- SQL

    /** Reads one row of a query result. */
    public interface Row<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** INSERT / UPDATE / DELETE with ? parameters; returns the rows changed. */
    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    /** SELECT with ? parameters. */
    public synchronized <T> List<T> query(String sql, Row<T> row, Object... args) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(row.read(rs));
            }
        } catch (SQLException e) {
            throw failed(e);
        }
        return out;
    }

    /** The first row of a SELECT, if any (a null value counts as none). */
    public <T> Optional<T> first(String sql, Row<T> row, Object... args) {
        List<T> rows = query(sql, row, args);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** Runs {@code work} as one transaction: all of it is saved, or none of it if it fails. Can be nested. */
    public synchronized void transaction(Runnable work) {
        try {
            if (depth++ == 0) {
                db.setAutoCommit(false);
            }
            boolean ok = false;
            try {
                work.run();
                ok = true;
            } finally {
                if (--depth == 0) {
                    if (ok) {
                        db.commit();
                    } else {
                        db.rollback();
                    }
                    db.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            ps.setObject(i + 1, a instanceof Boolean b ? (b ? 1 : 0) : a instanceof Instant t ? t.toEpochMilli() : a);
        }
        return ps;
    }

    /** A column as a whole number, or null. */
    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** A column as a decimal number, or null. */
    public static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    // ---------------------------------------------------------------- refresh times and calls left

    /** Records when something finished. */
    public void markRefreshed(String name, Instant when) {
        update("INSERT INTO refreshes (name, finished_at) VALUES (?, ?) "
                + "ON CONFLICT(name) DO UPDATE SET finished_at = excluded.finished_at", name, when);
    }

    public Optional<Instant> refreshedAt(String name) {
        return first("SELECT finished_at FROM refreshes WHERE name = ?", rs -> Instant.ofEpochMilli(rs.getLong(1)), name);
    }

    /** Saves an API's calls-left numbers (limit, remaining, reset, checkedAt). */
    public void saveUsage(String api, Map<String, Object> usage) {
        update("INSERT INTO api_usage (api, call_limit, remaining, resets, checked_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(api) DO UPDATE SET call_limit = excluded.call_limit, remaining = excluded.remaining, "
                + "resets = excluded.resets, checked_at = excluded.checked_at",
                api, text(usage.get("limit")), text(usage.get("remaining")), text(usage.get("reset")), text(usage.get("checkedAt")));
    }

    public Optional<Map<String, Object>> usage(String api) {
        return first("SELECT call_limit, remaining, resets, checked_at FROM api_usage WHERE api = ?", rs -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("limit", rs.getString(1));
            u.put("remaining", rs.getString(2));
            u.put("reset", rs.getString(3));
            u.put("checkedAt", rs.getString(4));
            return u;
        }, api);
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ---------------------------------------------------------------- housekeeping

    /**
     * Gives back unused space once the app has started (SQLite keeps freed space inside the file),
     * when more than a quarter of the file is unused.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void compact() {
        long free = first("PRAGMA freelist_count", rs -> rs.getLong(1)).orElse(0L);
        long total = first("PRAGMA page_count", rs -> rs.getLong(1)).orElse(0L);
        if (total > 0 && free * 4 > total) {
            try (Statement s = db.createStatement()) {
                s.execute("VACUUM");
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                log.info("Compacted {}: {} of {} pages were unused", file, free, total);
            } catch (SQLException e) {
                log.warn("Couldn't compact {}: {}", file, e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.warn("Couldn't close {}: {}", file, e.getMessage());
        }
    }

    private IllegalStateException failed(SQLException e) {
        return new IllegalStateException("Database error (" + file + "): " + e.getMessage(), e);
    }
}
