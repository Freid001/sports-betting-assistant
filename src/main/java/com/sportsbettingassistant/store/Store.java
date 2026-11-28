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
 * Everything the app saves, in one SQLite file (where: chosen on the Settings page; in memory until then).
 * The tables are listed in {@link #SCHEMA}; each part of the app reads and writes its own tables
 * (FootballTables, OddsCacheService, LiveService, FavouritesController) through the SQL helpers here.
 */
@Component
public class Store implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Store.class);

    /** Columns added after a table was first made: added to older databases (ignored if already there). */
    static final List<String> NEW_COLUMNS = List.of(
            "ALTER TABLE scorers ADD COLUMN position TEXT",   // e.g. "Forwards"
            // a squad player's season so far (GOAL API's squad list): goals per game for the goalscorer verdicts
            "ALTER TABLE squads ADD COLUMN games INTEGER",
            "ALTER TABLE squads ADD COLUMN goals INTEGER",
            "ALTER TABLE squads ADD COLUMN shots INTEGER",
            "ALTER TABLE squads ADD COLUMN stats TEXT",   // his season stats (JSON), for the player pop-up
            // 1: found elsewhere in GOAL API (e.g. Cole Palmer, filed under England), kept when the squad is re-saved
            "ALTER TABLE squads ADD COLUMN added INTEGER");

    /** All tables. Times are milliseconds since 1970 (UTC) unless the column says _iso. */
    static final String SCHEMA = """
        -- when something last finished: 'stats' (Refresh Stats), 'live', 'live-list', 'matches/PL/FINISHED'...
        CREATE TABLE IF NOT EXISTS refreshes (name TEXT PRIMARY KEY, finished_at INTEGER NOT NULL);
        -- app choices kept on the server, e.g. the bookmaker picked in Refresh Odds (pages and live odds both use it)
        CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT);
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
        -- which came back, and the cost
        CREATE TABLE IF NOT EXISTS odds_extra_fetches (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL,
            fetched_at_iso TEXT NOT NULL, markets TEXT, credits INTEGER, PRIMARY KEY (event_id, bookmaker));
        -- in-play prices, one fetch per match, bookmaker and bet window (30, HT, 60, 80), markets is JSON
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
        -- a game's stats at a point in the match ('30', 'HT', '60', '80', 'FT'): stats is the JSON the page shows
        CREATE TABLE IF NOT EXISTS live_snapshots (game_id TEXT NOT NULL, period TEXT NOT NULL, home_score TEXT,
            away_score TEXT, stats TEXT NOT NULL, saved_at TEXT, PRIMARY KEY (game_id, period));
        -- what happened in a followed game (goal, yellow, red, penalty, sub, shot, save, corner, offside, commentary
        -- moments, kickoff, ht, second, ft): the match page's Timeline card (see MatchTimeline)
        -- (source: 'api' for GOAL API's own feeds, with real minutes, or 'seen' for what the refreshes spotted)
        CREATE TABLE IF NOT EXISTS live_timeline (game_id TEXT NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL,
            side TEXT, player TEXT, assist TEXT, info TEXT, score TEXT, minute TEXT, half INTEGER, elapsed INTEGER,
            sort_order INTEGER, source TEXT, at TEXT, PRIMARY KEY (game_id, seq));
        -- a finished game's highlights video (GOAL API /videos/match): url empty = none yet, checked_at for re-checking
        CREATE TABLE IF NOT EXISTS live_videos (game_id TEXT PRIMARY KEY, url TEXT, title TEXT, checked_at TEXT);
        -- the prediction log: what the model said before a game (point PRE) and at each bet window (30, HT, 60, 80;
        -- 10 and 25 in older logs), scored against the result later. body: the prediction as the page sent it (JSON)
        CREATE TABLE IF NOT EXISTS predictions (match_id TEXT NOT NULL, point TEXT NOT NULL, kickoff TEXT NOT NULL,
            competition TEXT, home TEXT, away TEXT, home_team_id INTEGER, away_team_id INTEGER, saved_at TEXT,
            version TEXT, body TEXT NOT NULL, PRIMARY KEY (match_id, point));
        -- games to follow live once GOAL API lists them (on match day), picked with the match page's play button
        CREATE TABLE IF NOT EXISTS live_follow (tournament TEXT NOT NULL, kickoff TEXT NOT NULL, home TEXT NOT NULL,
            away TEXT NOT NULL, PRIMARY KEY (tournament, kickoff, home));
        -- GOAL API's own id for each team (squads and head to head are asked for by it)
        CREATE TABLE IF NOT EXISTS goal_teams (goal_id TEXT PRIMARY KEY, team_id INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS goal_teams_by_team ON goal_teams (team_id);
        -- league tables as GOAL API gives them (every game this season, even ones missing from its results)
        CREATE TABLE IF NOT EXISTS standings (competition TEXT NOT NULL, team_id INTEGER NOT NULL, team_name TEXT,
            position INTEGER, played INTEGER, won INTEGER, draw INTEGER, lost INTEGER, goals_for INTEGER,
            goals_against INTEGER, points INTEGER, grp TEXT, PRIMARY KEY (competition, team_id));
        """;

    /**
     * Clean-ups of older versions' data, run after the tables (each does nothing once done). Safe to delete a line
     * once no database from before it is in use.
     */
    static final String LEGACY_CLEANUP = """
        -- tables earlier versions used: the key -> JSON table (and its import marker), odds_extras (one row per match,
        -- now odds_extra_fetches), odds_live (only the last fetch, now odds_live_points), live_events (now
        -- live_timeline), goal_badges
        DROP TABLE IF EXISTS saved;
        DELETE FROM refreshes WHERE name = 'old-files-imported';
        DROP TABLE IF EXISTS odds_extras;
        DROP TABLE IF EXISTS odds_live;
        DROP TABLE IF EXISTS live_events;
        DROP TABLE IF EXISTS goal_badges;
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

    /**
     * Schema changes from version 2 on, in order: MIGRATIONS.get(0) takes a database from version 1 (the tables
     * above) to 2, the next to 3, and so on. Each runs once, in a transaction, and records its version in SQLite's
     * user_version. A database file that has data gets a backup copy first (see backupBefore).
     */
    static final List<Migration> MIGRATIONS = List.of(
            // 2: one identity per game. matches becomes games (same ids); each API's own id for a game is a row in
            // game_sources ('goal' GOAL API's fixture id, 'goal-number' its numeric apiId, 'odds' The Odds API's
            // event id), and each API's spelling of a team is a row in team_names. Followed games by game id.
            () -> """
                CREATE TABLE games (id INTEGER PRIMARY KEY, competition TEXT NOT NULL, kickoff_at TEXT NOT NULL,
                    home_team_id INTEGER NOT NULL REFERENCES teams (id), away_team_id INTEGER NOT NULL REFERENCES teams (id),
                    status TEXT, stage TEXT, grp TEXT, matchday INTEGER, home_goals INTEGER, away_goals INTEGER,
                    ht_home INTEGER, ht_away INTEGER);
                INSERT INTO games (id, competition, kickoff_at, home_team_id, away_team_id, status, stage, grp, matchday,
                    home_goals, away_goals, ht_home, ht_away)
                    SELECT id, competition, utc_date, home_team_id, away_team_id, status, stage, grp, matchday,
                        home_goals, away_goals, ht_home, ht_away
                    FROM matches WHERE utc_date IS NOT NULL AND home_team_id IS NOT NULL AND away_team_id IS NOT NULL;
                CREATE INDEX games_by_home ON games (home_team_id, kickoff_at);
                CREATE INDEX games_by_away ON games (away_team_id, kickoff_at);
                CREATE INDEX games_by_competition ON games (competition, status);
                CREATE TABLE game_sources (source TEXT NOT NULL, source_id TEXT NOT NULL,
                    game_id INTEGER NOT NULL REFERENCES games (id), PRIMARY KEY (source, source_id));
                CREATE INDEX game_sources_by_game ON game_sources (game_id, source);
                INSERT INTO game_sources (source, source_id, game_id) SELECT 'goal-number', CAST(id AS TEXT), id FROM games;
                DROP TABLE matches;
                CREATE TABLE team_names (source TEXT NOT NULL, name TEXT NOT NULL,
                    team_id INTEGER NOT NULL REFERENCES teams (id), PRIMARY KEY (source, name));
                DROP TABLE live_follow;
                CREATE TABLE live_follow (game_id INTEGER PRIMARY KEY REFERENCES games (id));
                """,
            // 3: one identity per player. GOAL API numbers a player one way in squads and another in its scorer
            // lists: each is a row in player_sources ('goal' squad apiId, 'goal-scorer' playerKey) pointing at one
            // players row. Who plays for whom in team_players, season stats one row per stat (no JSON), scorers
            // by player id (no copied names). Squads and scorers come back with the next Refresh Stats.
            () -> """
                CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT NOT NULL, position TEXT, age INTEGER, image TEXT);
                CREATE TABLE player_sources (source TEXT NOT NULL, source_id TEXT NOT NULL,
                    player_id INTEGER NOT NULL REFERENCES players (id), PRIMARY KEY (source, source_id));
                CREATE INDEX player_sources_by_player ON player_sources (player_id, source);
                CREATE TABLE team_players (team_id INTEGER NOT NULL REFERENCES teams (id),
                    player_id INTEGER NOT NULL REFERENCES players (id), shirt INTEGER, captain INTEGER, injured INTEGER,
                    filed_under TEXT, looked_up INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (team_id, player_id));
                CREATE INDEX team_players_by_player ON team_players (player_id);
                CREATE TABLE player_season_stats (player_id INTEGER NOT NULL REFERENCES players (id),
                    team_id INTEGER NOT NULL REFERENCES teams (id), stat TEXT NOT NULL, value REAL NOT NULL,
                    PRIMARY KEY (player_id, team_id, stat));
                DROP TABLE squads;
                DROP TABLE scorers;
                CREATE TABLE scorers (competition TEXT NOT NULL, player_id INTEGER NOT NULL REFERENCES players (id),
                    team_id INTEGER REFERENCES teams (id), goals INTEGER, assists INTEGER, penalties INTEGER, games INTEGER,
                    PRIMARY KEY (competition, player_id));
                DELETE FROM refreshes WHERE name LIKE 'scorers/%' OR name LIKE 'squad/%' OR name LIKE 'squad-photos/%'
                    OR name LIKE 'squad-full/%' OR name LIKE 'player-lookup%';
                """,
            // 4: the live tables typed and keyed by game (games.id): numbers as numbers, the stats at each point as
            // rows (no JSON), the game's names and kick-off from games. live_points: the latest ('LIVE') and each
            // saved point ('30', 'HT', '60', '80', 'FT'); live_point_stats: their stat rows; live_shooters: the players
            // with shots; live_events: the Timeline.
            // Live games are linked to their game first (GOAL API's names are its teams' short names); one that can't
            // be isn't carried over (Post-Match loads it again).
            () -> """
                INSERT OR IGNORE INTO game_sources (source, source_id, game_id)
                    SELECT 'goal', l.id, g.id FROM live_games l JOIN games g
                        ON ABS(julianday(g.kickoff_at) - julianday(l.kickoff)) < 1.5
                        JOIN teams h ON h.id = g.home_team_id JOIN teams a ON a.id = g.away_team_id
                    WHERE (h.short_name = l.home OR h.name = l.home) AND (a.short_name = l.away OR a.name = l.away)
                    ORDER BY l.id, ABS(julianday(g.kickoff_at) - julianday(l.kickoff));
                CREATE TABLE live_state (game_id INTEGER PRIMARY KEY REFERENCES games (id), league_id TEXT, status TEXT,
                    live INTEGER NOT NULL DEFAULT 0, minute TEXT, elapsed INTEGER, extra INTEGER, period TEXT,
                    home_score INTEGER, away_score INTEGER, ht_home INTEGER, ht_away INTEGER, updated_at TEXT, ticked INTEGER);
                INSERT INTO live_state (game_id, league_id, status, live, minute, elapsed, extra, period, home_score,
                    away_score, updated_at, ticked)
                    SELECT s.game_id, g.league_id, g.status, COALESCE(g.live, 0), g.minute, CASE WHEN TRIM(g.elapsed) GLOB '[0-9]*' THEN CAST(TRIM(g.elapsed) AS INTEGER) END,
                        CASE WHEN TRIM(g.extra) GLOB '[0-9]*' THEN CAST(TRIM(g.extra) AS INTEGER) END, g.period, CASE WHEN TRIM(g.home_score) GLOB '[0-9]*' THEN CAST(TRIM(g.home_score) AS INTEGER) END, CASE WHEN TRIM(g.away_score) GLOB '[0-9]*' THEN CAST(TRIM(g.away_score) AS INTEGER) END,
                        g.updated_at, g.ticked
                    FROM live_games g JOIN game_sources s ON s.source = 'goal' AND s.source_id = g.id;
                CREATE TABLE live_points (game_id INTEGER NOT NULL REFERENCES games (id), point TEXT NOT NULL, minute TEXT,
                    home_score INTEGER, away_score INTEGER, xg_home REAL, xg_away REAL, final INTEGER, saved_at TEXT,
                    PRIMARY KEY (game_id, point));
                INSERT INTO live_points (game_id, point, minute, xg_home, xg_away, final, saved_at)
                    SELECT s.game_id, 'LIVE', o.minute, o.xg_home, o.xg_away, o.final, o.updated_at
                    FROM live_stats o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                INSERT INTO live_points (game_id, point, home_score, away_score, xg_home, xg_away, saved_at)
                    SELECT s.game_id, o.period, CASE WHEN TRIM(o.home_score) GLOB '[0-9]*' THEN CAST(TRIM(o.home_score) AS INTEGER) END, CASE WHEN TRIM(o.away_score) GLOB '[0-9]*' THEN CAST(TRIM(o.away_score) AS INTEGER) END,
                        json_extract(o.stats, '$.xg[0]'), json_extract(o.stats, '$.xg[1]'), o.saved_at
                    FROM live_snapshots o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                CREATE TABLE live_point_stats (game_id INTEGER NOT NULL REFERENCES games (id), point TEXT NOT NULL,
                    position INTEGER NOT NULL, stat TEXT NOT NULL, home REAL, away REAL, unit TEXT,
                    PRIMARY KEY (game_id, point, position));
                INSERT INTO live_point_stats (game_id, point, position, stat, home, away, unit)
                    SELECT s.game_id, 'LIVE', o.position, o.label, CASE WHEN o.home IS NULL OR TRIM(o.home) IN ('', 'null') THEN NULL ELSE CAST(REPLACE(o.home, '%', '') AS REAL) END, CASE WHEN o.away IS NULL OR TRIM(o.away) IN ('', 'null') THEN NULL ELSE CAST(REPLACE(o.away, '%', '') AS REAL) END,
                        CASE WHEN instr(o.home, '%') > 0 THEN '%' END
                    FROM live_stat_rows o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                INSERT INTO live_point_stats (game_id, point, position, stat, home, away, unit)
                    SELECT s.game_id, o.period, r.key, json_extract(r.value, '$.label'),
                        CASE WHEN json_extract(r.value, '$.home') IS NULL OR TRIM(json_extract(r.value, '$.home')) IN ('', 'null') THEN NULL ELSE CAST(REPLACE(json_extract(r.value, '$.home'), '%', '') AS REAL) END, CASE WHEN json_extract(r.value, '$.away') IS NULL OR TRIM(json_extract(r.value, '$.away')) IN ('', 'null') THEN NULL ELSE CAST(REPLACE(json_extract(r.value, '$.away'), '%', '') AS REAL) END,
                        CASE WHEN instr(json_extract(r.value, '$.home'), '%') > 0 THEN '%' END
                    FROM live_snapshots o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id,
                        json_each(o.stats, '$.rows') r;
                CREATE TABLE live_shooters (game_id INTEGER NOT NULL REFERENCES games (id), side TEXT NOT NULL,
                    position INTEGER NOT NULL, name TEXT, shots INTEGER, on_target INTEGER, goals INTEGER,
                    PRIMARY KEY (game_id, side, position));
                INSERT INTO live_shooters SELECT s.game_id, o.side, o.position, o.name, o.shots, o.on_target, o.goals
                    FROM live_players o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                CREATE TABLE live_events (game_id INTEGER NOT NULL REFERENCES games (id), seq INTEGER NOT NULL,
                    kind TEXT NOT NULL, side TEXT, player TEXT, assist TEXT, info TEXT, score TEXT, minute TEXT, half INTEGER,
                    elapsed INTEGER, sort_order INTEGER, source TEXT, seen_at TEXT, PRIMARY KEY (game_id, seq));
                INSERT INTO live_events SELECT s.game_id, o.seq, o.kind, o.side, o.player, o.assist, o.info, o.score, o.minute,
                    o.half, o.elapsed, o.sort_order, o.source, o.at
                    FROM live_timeline o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                CREATE TABLE game_videos (game_id INTEGER PRIMARY KEY REFERENCES games (id), url TEXT, title TEXT,
                    checked_at TEXT);
                INSERT INTO game_videos SELECT s.game_id, o.url, o.title, o.checked_at
                    FROM live_videos o JOIN game_sources s ON s.source = 'goal' AND s.source_id = o.game_id;
                DROP TABLE live_games;
                DROP TABLE live_stats;
                DROP TABLE live_stat_rows;
                DROP TABLE live_snapshots;
                DROP TABLE live_players;
                DROP TABLE live_timeline;
                DROP TABLE live_videos;
                """,
            // 5: no comma-separated lists or JSON in the odds and settings. A setting that's a list (Refresh Odds'
            // markets, the bet windows) is rows in setting_values; what a fetch asked for and got (markets, bookmakers)
            // is rows next to the fetch; the in-play prices at each bet window are rows in odds_live_prices.
            () -> """
                CREATE TABLE setting_values (setting TEXT NOT NULL, position INTEGER NOT NULL, value TEXT NOT NULL,
                    PRIMARY KEY (setting, value));
                WITH RECURSIVE split (setting, rest, item, pos) AS (
                        SELECT name, value || ',', '', -1 FROM settings WHERE name IN ('refresh-markets', 'bet-windows')
                        UNION ALL SELECT setting, substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1)), pos + 1
                        FROM split WHERE rest <> '')
                    INSERT INTO setting_values (setting, position, value) SELECT setting, pos, item FROM split WHERE pos >= 0 AND item <> '';
                INSERT INTO settings (name, value) SELECT 'bet-windows-picked', 'on' FROM settings WHERE name = 'bet-windows';
                DELETE FROM settings WHERE name IN ('refresh-markets', 'bet-windows');
                CREATE TABLE odds_fetch_markets (sport TEXT NOT NULL REFERENCES odds_fetches (sport), market TEXT NOT NULL,
                    PRIMARY KEY (sport, market));
                WITH RECURSIVE split (sport, rest, item, pos) AS (
                        SELECT sport, COALESCE(markets, '') || ',', '', -1 FROM odds_fetches
                        UNION ALL SELECT sport, substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1)), pos + 1
                        FROM split WHERE rest <> '')
                    INSERT OR IGNORE INTO odds_fetch_markets (sport, market) SELECT sport, item FROM split WHERE pos >= 0 AND item <> '';
                CREATE TABLE odds_fetch_bookmakers (sport TEXT NOT NULL REFERENCES odds_fetches (sport), bookmaker TEXT NOT NULL,
                    PRIMARY KEY (sport, bookmaker));
                WITH RECURSIVE split (sport, rest, item, pos) AS (
                        SELECT sport, COALESCE(bookmakers, '') || ',', '', -1 FROM odds_fetches
                        UNION ALL SELECT sport, substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1)), pos + 1
                        FROM split WHERE rest <> '')
                    INSERT OR IGNORE INTO odds_fetch_bookmakers (sport, bookmaker) SELECT sport, item FROM split WHERE pos >= 0 AND item <> '';
                CREATE TABLE odds_fetches_new (sport TEXT PRIMARY KEY, fetched_at TEXT NOT NULL, days INTEGER, credits INTEGER);
                INSERT INTO odds_fetches_new SELECT sport, fetched_at_iso, days,
                    CASE WHEN TRIM(credits) GLOB '[0-9]*' THEN CAST(TRIM(credits) AS INTEGER) END FROM odds_fetches;
                DROP TABLE odds_fetches;
                ALTER TABLE odds_fetches_new RENAME TO odds_fetches;
                CREATE TABLE odds_extra_fetch_markets (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL, market TEXT NOT NULL,
                    PRIMARY KEY (event_id, bookmaker, market));
                WITH RECURSIVE split (event_id, bookmaker, rest, item, pos) AS (
                        SELECT event_id, bookmaker, COALESCE(markets, '') || ',', '', -1 FROM odds_extra_fetches
                        UNION ALL SELECT event_id, bookmaker, substr(rest, instr(rest, ',') + 1), trim(substr(rest, 1, instr(rest, ',') - 1)), pos + 1
                        FROM split WHERE rest <> '')
                    INSERT OR IGNORE INTO odds_extra_fetch_markets (event_id, bookmaker, market)
                    SELECT event_id, bookmaker, item FROM split WHERE pos >= 0 AND item <> '';
                CREATE TABLE odds_extra_fetches_new (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL, fetched_at TEXT NOT NULL,
                    credits INTEGER, PRIMARY KEY (event_id, bookmaker));
                INSERT INTO odds_extra_fetches_new SELECT event_id, bookmaker, fetched_at_iso, credits FROM odds_extra_fetches;
                DROP TABLE odds_extra_fetches;
                ALTER TABLE odds_extra_fetches_new RENAME TO odds_extra_fetches;
                CREATE TABLE odds_live_prices (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL, point TEXT NOT NULL,
                    position INTEGER NOT NULL, market TEXT NOT NULL, outcome TEXT, description TEXT, line REAL, price REAL,
                    PRIMARY KEY (event_id, bookmaker, point, position));
                INSERT INTO odds_live_prices (event_id, bookmaker, point, position, market, outcome, description, line, price)
                    SELECT p.event_id, p.bookmaker, p.point, m.key * 1000 + o.key, json_extract(m.value, '$.key'),
                        json_extract(o.value, '$.name'), json_extract(o.value, '$.description'),
                        json_extract(o.value, '$.point'), json_extract(o.value, '$.price')
                    FROM odds_live_points p, json_each(p.markets) m, json_each(m.value, '$.outcomes') o;
                CREATE TABLE odds_live_points_new (event_id TEXT NOT NULL, bookmaker TEXT NOT NULL, point TEXT NOT NULL,
                    fetched_at TEXT, credits INTEGER, PRIMARY KEY (event_id, bookmaker, point));
                INSERT INTO odds_live_points_new SELECT event_id, bookmaker, point, fetched_at_iso, credits FROM odds_live_points;
                DROP TABLE odds_live_points;
                ALTER TABLE odds_live_points_new RENAME TO odds_live_points;
                """,
            // 6: predictions by game (games.id) and as rows, not a JSON body: the model's numbers (chances, its view,
            // ours after the nudge, the bookmaker's, the odds) in prediction_numbers, the Prediction card's lines in
            // prediction_lines, the recommended bets in prediction_bets. Names, kick-off and competition from games.
            () -> """
                CREATE TABLE predictions_new (game_id INTEGER NOT NULL REFERENCES games (id), point TEXT NOT NULL,
                    saved_at TEXT, model_version TEXT, confidence TEXT, minute INTEGER, home_goals INTEGER, away_goals INTEGER,
                    has_bets INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (game_id, point));
                INSERT OR IGNORE INTO predictions_new (game_id, point, saved_at, model_version, confidence, minute, home_goals,
                    away_goals, has_bets)
                    SELECT s.game_id, p.point, p.saved_at, p.version, json_extract(p.body, '$.confidence'),
                        json_extract(p.body, '$.minute'), json_extract(p.body, '$.score[0]'), json_extract(p.body, '$.score[1]'),
                        json_type(p.body, '$.recommended') IS 'array'
                    FROM predictions p JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id
                    ORDER BY p.saved_at DESC;
                CREATE TABLE prediction_numbers (game_id INTEGER NOT NULL, point TEXT NOT NULL, kind TEXT NOT NULL,
                    name TEXT NOT NULL, value REAL NOT NULL, PRIMARY KEY (game_id, point, kind, name),
                    FOREIGN KEY (game_id, point) REFERENCES predictions (game_id, point));
                INSERT OR IGNORE INTO prediction_numbers (game_id, point, kind, name, value)
                    SELECT s.game_id, p.point, 'chances', j.key, j.value FROM predictions p
                    JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id, json_each(p.body, '$.chances') j
                    WHERE j.type IN ('integer', 'real');
                INSERT OR IGNORE INTO prediction_numbers (game_id, point, kind, name, value)
                    SELECT s.game_id, p.point, 'view', j.key, j.value FROM predictions p
                    JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id, json_each(p.body, '$.view') j
                    WHERE j.type IN ('integer', 'real');
                INSERT OR IGNORE INTO prediction_numbers (game_id, point, kind, name, value)
                    SELECT s.game_id, p.point, 'ours', j.key, j.value FROM predictions p
                    JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id, json_each(p.body, '$.ours') j
                    WHERE j.type IN ('integer', 'real');
                INSERT OR IGNORE INTO prediction_numbers (game_id, point, kind, name, value)
                    SELECT s.game_id, p.point, 'bookmaker', j.key, j.value FROM predictions p
                    JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id, json_each(p.body, '$.bookmaker') j
                    WHERE j.type IN ('integer', 'real');
                INSERT OR IGNORE INTO prediction_numbers (game_id, point, kind, name, value)
                    SELECT s.game_id, p.point, 'odds', j.key, j.value FROM predictions p
                    JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id, json_each(p.body, '$.odds') j
                    WHERE j.type IN ('integer', 'real');
                CREATE TABLE prediction_lines (game_id INTEGER NOT NULL, point TEXT NOT NULL, line_no INTEGER NOT NULL,
                    label TEXT, text TEXT, PRIMARY KEY (game_id, point, line_no),
                    FOREIGN KEY (game_id, point) REFERENCES predictions (game_id, point));
                INSERT OR IGNORE INTO prediction_lines (game_id, point, line_no, label, text)
                    SELECT s.game_id, p.point, r.key, json_extract(r.value, '$[0]'), json_extract(r.value, '$[1]')
                    FROM predictions p JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id,
                        json_each(p.body, '$.predictionRows') r;
                CREATE TABLE prediction_bets (game_id INTEGER NOT NULL, point TEXT NOT NULL, rank INTEGER NOT NULL,
                    market TEXT, label TEXT, price REAL, chance REAL, fits INTEGER, hint TEXT, ours REAL, fair REAL, ret REAL,
                    verdict TEXT, verdict_class TEXT, PRIMARY KEY (game_id, point, rank),
                    FOREIGN KEY (game_id, point) REFERENCES predictions (game_id, point));
                INSERT OR IGNORE INTO prediction_bets (game_id, point, rank, market, label, price, chance, fits, hint, ours,
                    fair, ret, verdict, verdict_class)
                    SELECT s.game_id, p.point, b.key, json_extract(b.value, '$.key'),
                        COALESCE(json_extract(b.value, '$.name'), json_extract(b.value, '$.label')),
                        COALESCE(json_extract(b.value, '$.price'), json_extract(b.value, '$.o.price')),
                        json_extract(b.value, '$.chance'),
                        CASE WHEN json_extract(b.value, '$.fits') IN (1, 'true') THEN 1
                            WHEN json_extract(b.value, '$.fits') IN (0, 'false') THEN 0 END,
                        json_extract(b.value, '$.hint'), json_extract(b.value, '$.r.ours'), json_extract(b.value, '$.r.fair'),
                        json_extract(b.value, '$.r.ret'), json_extract(b.value, '$.r.verdict.text'),
                        json_extract(b.value, '$.r.verdict.cls')
                    FROM predictions p JOIN game_sources s ON s.source = 'odds' AND s.source_id = p.match_id,
                        json_each(p.body, '$.recommended') b;
                DROP TABLE predictions;
                ALTER TABLE predictions_new RENAME TO predictions;
                """);

    /** One schema change: its SQL (statements separated by ';'), and Java for anything SQL can't do. */
    public interface Migration {
        String sql();

        default void after(Statement s) throws SQLException {
        }
    }

    /** The schema version the code expects. */
    public static int currentVersion() {
        return 1 + MIGRATIONS.size();
    }

    private final List<Migration> migrations;   // MIGRATIONS (tests can give others)

    private Path file;
    private Connection db;
    private int depth;   // nested transaction() calls

    /**
     * {@code file}: the database file. Blank (the app): the location chosen on the Settings page; with none chosen
     * yet, everything is kept in memory (no file at all) until Settings saves it where you choose.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public Store(@Value("${store.file:}") String file) {
        this(file == null || file.isBlank() ? chosen() : Path.of(file));
    }

    /** {@code file}: the database file, or null to keep everything in memory (until switchTo a file). */
    public Store(Path file) {
        this(file, MIGRATIONS);
    }

    /** As above, with these migrations instead of MIGRATIONS (for tests). */
    Store(Path file, List<Migration> migrations) {
        this.migrations = migrations;
        this.file = file;
        this.db = open(file, migrations);
    }

    private static Path chosen() {
        String s = StorageLocation.forUser().saved();
        return s == null ? null : Path.of(s);
    }

    /** Opens (or creates) a database file and brings its tables up to date; null: a database in memory. */
    /** Runs each statement of a script, skipping pieces that are only comments (a ';' in a comment splits it too). */
    private static void run(Statement s, String script) throws SQLException {
        for (String sql : script.split(";")) {
            String code = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
            if (!code.isBlank()) {
                s.execute(sql);
            }
        }
    }

    private static Connection open(Path file, List<Migration> migrations) {
        try {
            if (file != null && file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Connection db = DriverManager.getConnection(file == null ? "jdbc:sqlite::memory:" : "jdbc:sqlite:" + file);
            try (Statement s = db.createStatement()) {
                prepare(db, s, file, migrations);
            } catch (SQLException | RuntimeException e) {
                db.close();   // (not left open: the file stays free for another try)
                throw e;
            }
            return db;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Couldn't open the database " + (file == null ? "in memory" : file) + ": "
                    + e.getMessage(), e);
        }
    }

    /** Brings a just-opened database up to date: WAL, the version-1 tables, then the migrations. */
    private static void prepare(Connection db, Statement s, Path file, List<Migration> migrations) throws SQLException {
        s.execute("PRAGMA journal_mode=WAL");     // readers don't block the writer
        s.execute("PRAGMA busy_timeout=5000");
        int version = version(s);
        // worth a backup before migrating: anything but a new file (databases from before schema versions are at 0)
        boolean hadData = version >= 1 || hasTable(s, "settings");
        if (version <= 1) {   // version 1: the tables above (made, or brought up to date: each step is harmless twice)
            run(s, SCHEMA);
            for (String sql : NEW_COLUMNS) {
                try {
                    s.execute(sql);
                } catch (SQLException alreadyThere) {
                    // a database made after the column was added already has it
                }
            }
            run(s, LEGACY_CLEANUP);
            if (version == 0) {
                setVersion(s, 1);
                version = 1;
            }
        }
        migrate(db, s, hadData ? file : null, version, migrations);
    }

    private static boolean hasTable(Statement s, String name) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + name + "'")) {
            return rs.next();
        }
    }

    private static int version(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void setVersion(Statement s, int version) throws SQLException {
        s.execute("PRAGMA user_version = " + version);
    }

    /** Runs the migrations this database hasn't had yet, after backing it up (file: null for none, e.g. a new file). */
    private static void migrate(Connection db, Statement s, Path file, int version, List<Migration> migrations)
            throws SQLException {
        int latest = 1 + migrations.size();
        if (version > latest) {
            throw new SQLException("This database is from a newer version of the app (schema " + version + ").");
        }
        if (version == latest) {
            return;
        }
        backupBefore(s, file, version + 1);
        for (int v = version + 1; v <= latest; v++) {
            Migration m = migrations.get(v - 2);
            boolean auto = db.getAutoCommit();
            db.setAutoCommit(false);
            try {
                run(s, m.sql());
                m.after(s);
                setVersion(s, v);
                db.commit();
            } catch (SQLException | RuntimeException e) {
                db.rollback();
                throw new SQLException("Couldn't update the database to schema " + v + ": " + e.getMessage(), e);
            } finally {
                db.setAutoCommit(auto);
            }
            log.info("Database updated to schema {}", v);
        }
    }

    /**
     * Before changing a database file's tables: a copy of it as it was, next to it ("…/sports-betting-assistant
     * .before-schema-2.db"). Kept if there already is one (a first attempt's copy is the one worth keeping).
     */
    static Path backupBefore(Statement s, Path file, int toVersion) throws SQLException {
        if (file == null) {
            return null;
        }
        String name = file.getFileName().toString().replaceFirst("\\.db$", "");
        Path copy = file.resolveSibling(name + ".before-schema-" + toVersion + ".db");
        if (!Files.exists(copy)) {
            s.execute("VACUUM INTO '" + copy.toString().replace("'", "''") + "'");
            log.info("Backed up the database to {} before updating it", copy);
        }
        return copy;
    }

    /**
     * Carries on in another database file that already holds the same data (the Storage card copies it there first):
     * the new file is opened before the old one is closed, so a failure leaves things as they were.
     */
    public synchronized void switchTo(Path newFile) {
        if (depth > 0) {
            throw new IllegalStateException("Can't switch database files in the middle of saving.");
        }
        Connection next = open(newFile, migrations);
        Connection old = db;
        db = next;
        file = newFile;
        try {
            old.close();
        } catch (SQLException e) {
            log.warn("Couldn't close the old database: {}", e.getMessage());
        }
        log.info("Now using the database {}", newFile.toAbsolutePath());
    }

    /**
     * Deletes everything: the database file (and its -wal / -shm files) is removed and a new empty one made in its
     * place. Can't be undone.
     */
    public synchronized void wipe() {
        if (file == null) {
            throw new IllegalStateException("Nothing is saved to a file yet.");
        }
        if (depth > 0) {
            throw new IllegalStateException("Can't delete the database in the middle of saving.");
        }
        try {
            db.close();
        } catch (SQLException e) {
            log.warn("Couldn't close {}: {}", file, e.getMessage());
        }
        try {
            for (String end : new String[] {"", "-wal", "-shm", "-journal"}) {
                Files.deleteIfExists(Path.of(file + end));
            }
        } catch (IOException e) {
            db = open(file, migrations);   // keep working with what's there
            throw new IllegalStateException("Couldn't delete " + file + ": " + e.getMessage(), e);
        }
        db = open(file, migrations);
        log.info("Deleted all data: {} is empty now", file.toAbsolutePath());
    }

    /** The database file, or null while everything is in memory (no location chosen yet). */
    public synchronized Path file() {
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
