package com.sportsbettingassistant.football;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.sportsbettingassistant.football.live.TeamNames;
import com.sportsbettingassistant.store.Store;

/**
 * One identity per real game. Each API has its own id for a game (GOAL API's fixture id, its numeric apiId, The Odds
 * API's event id): they're rows in game_sources pointing at one row in games. A game is found by its teams and
 * kick-off once, when an API first shows it, and by its id from then on.
 * <p>
 * Teams are GOAL API's (the teams table). The Odds API names them its own way ("Nottingham Forest" for GOAL API's
 * "Nottingham"): each spelling is matched to a team once and kept in team_names.
 */
@Component
public class Games {

    /** game_sources.source: GOAL API's fixture id ("cmu74igq..."), its numeric apiId, The Odds API's event id. */
    public static final String GOAL = "goal", GOAL_NUMBER = "goal-number", ODDS = "odds";

    /** Two sources' kick-offs for the same game can differ a little (and a game can be moved by a few hours). */
    static final Duration SAME_GAME = Duration.ofHours(36);

    private static final Logger log = LoggerFactory.getLogger(Games.class);

    private final Store store;

    public Games(Store store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- teams

    /**
     * The team an API means by this name in a competition (code "PL"...): the saved match, or the competition's team
     * whose name, short name or code is clearly the most alike (TeamNames.similar 2 or more, no tie), then saved.
     */
    public Optional<Long> team(String source, String code, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        synchronized (store) {   // (one lock with the store's: any Games instance, any thread)
            return findTeam(source, code, name);
        }
    }

    private Optional<Long> findTeam(String source, String code, String name) {
        Optional<Long> saved = store.first("SELECT team_id FROM team_names WHERE source = ? AND name = ?",
                rs -> rs.getLong(1), source, name);
        if (saved.isPresent()) {
            return saved;
        }
        List<Object[]> pool = store.query("SELECT t.id, t.name, t.short_name, t.tla FROM teams t "
                + "JOIN competition_teams c ON c.team_id = t.id WHERE c.competition = ?",
                rs -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)}, code);
        if (pool.isEmpty()) {   // a competition whose teams aren't saved: every team
            pool = store.query("SELECT id, name, short_name, tla FROM teams",
                    rs -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)});
        }
        Long best = null;
        int bestScore = 1, alike = 0;   // alike: teams with the best score (more than one: can't tell)
        for (Object[] t : pool) {
            int score = Math.max(TeamNames.similar(name, t[1]), TeamNames.similar(name, t[2]));
            if (t[3] != null && String.valueOf(t[3]).equalsIgnoreCase(name.trim())) {
                score = 3;
            }
            if (score > bestScore) {
                best = (Long) t[0];
                bestScore = score;
                alike = 1;
            } else if (score == bestScore && best != null) {
                alike++;
            }
        }
        if (best == null || alike > 1) {
            log.info("No team for {} name \"{}\" in {}", source, name, code);
            return Optional.empty();
        }
        store.update("INSERT OR REPLACE INTO team_names (source, name, team_id) VALUES (?, ?, ?)", source, name, best);
        return Optional.of(best);
    }

    // ---------------------------------------------------------------- games

    /** The game an API's id is for, if it's been linked. */
    public Optional<Long> game(String source, String sourceId) {
        return sourceId == null ? Optional.empty()
                : store.first("SELECT game_id FROM game_sources WHERE source = ? AND source_id = ?", rs -> rs.getLong(1),
                        source, sourceId);
    }

    /** An API's id for a game, if known. */
    public Optional<String> sourceId(long gameId, String source) {
        return store.first("SELECT source_id FROM game_sources WHERE game_id = ? AND source = ? LIMIT 1",
                rs -> rs.getString(1), gameId, source);
    }

    /** A game: {id, competition, kickoffAt, homeTeamId, awayTeamId, status, homeGoals, awayGoals, htHome, htAway}. */
    public Optional<Map<String, Object>> get(long gameId) {
        return store.first("SELECT * FROM games WHERE id = ?", rs -> {
            Map<String, Object> g = new java.util.LinkedHashMap<>();
            g.put("id", rs.getLong("id"));
            g.put("competition", rs.getString("competition"));
            g.put("kickoffAt", rs.getString("kickoff_at"));
            g.put("homeTeamId", rs.getLong("home_team_id"));
            g.put("awayTeamId", rs.getLong("away_team_id"));
            g.put("status", rs.getString("status"));
            g.put("homeGoals", Store.longOrNull(rs, "home_goals"));
            g.put("awayGoals", Store.longOrNull(rs, "away_goals"));
            g.put("htHome", Store.longOrNull(rs, "ht_home"));
            g.put("htAway", Store.longOrNull(rs, "ht_away"));
            return g;
        }, gameId);
    }

    /**
     * The game an API's id is for: the one it's linked to, else the game with these teams kicking off within a day
     * and a half (any competition: a cup game is in both lists), else a new game. Then linked, so it's found by id
     * from now on. Empty if the teams aren't known.
     */
    public Optional<Long> link(String source, String sourceId, String code, String kickoff, Long homeTeamId,
            Long awayTeamId) {
        synchronized (store) {
            return linkTeams(source, sourceId, code, kickoff, homeTeamId, awayTeamId);
        }
    }

    private Optional<Long> linkTeams(String source, String sourceId, String code, String kickoff, Long homeTeamId,
            Long awayTeamId) {
        Optional<Long> linked = game(source, sourceId);
        if (linked.isPresent() || homeTeamId == null || awayTeamId == null || instant(kickoff) == null || code == null) {
            return linked;
        }
        long[] id = {0};
        store.transaction(() -> {
            id[0] = find(source, homeTeamId, awayTeamId, instant(kickoff)).orElseGet(() -> {
                store.update("INSERT INTO games (competition, kickoff_at, home_team_id, away_team_id) VALUES (?, ?, ?, ?)",
                        code, instant(kickoff).toString(), homeTeamId, awayTeamId);
                return store.first("SELECT last_insert_rowid()", rs -> rs.getLong(1)).orElseThrow();
            });
            store.update("INSERT OR REPLACE INTO game_sources (source, source_id, game_id) VALUES (?, ?, ?)",
                    source, sourceId, id[0]);
        });
        return Optional.of(id[0]);
    }

    /** As link, with the teams as that API names them (matched with team()). */
    public Optional<Long> linkByNames(String source, String sourceId, String code, String kickoff, String home, String away) {
        Optional<Long> linked = game(source, sourceId);
        if (linked.isPresent()) {
            return linked;
        }
        Optional<Long> h = team(source, code, home), a = team(source, code, away);
        return h.isPresent() && a.isPresent() ? link(source, sourceId, code, kickoff, h.get(), a.get()) : Optional.empty();
    }

    /**
     * The game with these teams (home and away) kicking off near this time, if there is one that this source hasn't
     * got another id for (an API's two ids are two games).
     */
    Optional<Long> find(String source, long homeTeamId, long awayTeamId, Instant kickoff) {
        List<Object[]> near = new ArrayList<>();
        for (Object[] g : store.query("SELECT id, kickoff_at FROM games g WHERE home_team_id = ? AND away_team_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM game_sources s WHERE s.game_id = g.id AND s.source = ?)",
                rs -> new Object[] {rs.getLong(1), rs.getString(2)}, homeTeamId, awayTeamId, source)) {
            Instant at = instant((String) g[1]);
            if (at != null && Duration.between(at, kickoff).abs().compareTo(SAME_GAME) <= 0) {
                near.add(new Object[] {g[0], Duration.between(at, kickoff).abs()});
            }
        }
        return near.stream().min((x, y) -> ((Duration) x[1]).compareTo((Duration) y[1])).map(g -> (Long) g[0]);
    }

    /** Another API id for a game already known (GOAL API's fixture id next to its apiId, say). */
    public void alsoKnownAs(long gameId, String source, String sourceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            store.update("INSERT OR REPLACE INTO game_sources (source, source_id, game_id) VALUES (?, ?, ?)",
                    source, sourceId, gameId);
        }
    }

    // ---------------------------------------------------------------- odds events

    /**
     * Links every saved Odds API event that isn't linked yet to its game (after a Refresh Odds, and at start-up for
     * what was saved before games existed). Returns how many were linked.
     */
    public int linkOddsEvents() {
        int n = 0;
        for (String[] e : store.query("SELECT id, sport, kickoff, home_team, away_team FROM odds_events e "
                + "WHERE NOT EXISTS (SELECT 1 FROM game_sources s WHERE s.source = 'odds' AND s.source_id = e.id)",
                rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})) {
            String code = Competitions.CODES.get(e[1].startsWith("soccer_") ? e[1].substring("soccer_".length()) : e[1]);
            if (code != null && linkByNames(ODDS, e[0], code, e[2], e[3], e[4]).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /** The Odds API's event id for a game, if it has one. */
    public Optional<String> oddsEvent(long gameId) {
        return sourceId(gameId, ODDS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void linkAtStart() {
        try {
            int n = linkOddsEvents();
            if (n > 0) {
                log.info("Linked {} odds events to their games", n);
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't link odds events to games: {}", e.getMessage());
        }
    }

    static Instant instant(String iso) {
        try {
            return iso == null || iso.isBlank() ? null : Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
