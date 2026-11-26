package com.sportsbettingassistant.football;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;

/**
 * Match-page stats (from GOAL API). Everything except head to head is saved by
 * "Refresh Stats" (StatsRefreshService) in the tables; these endpoints only read them.
 * Head to head is one call the first time a match page needs it.
 */
@RestController
@RequestMapping("/api/football")
public class FootballStatsController {

    /** Head to head is fetched when a match page first needs it, then reused for a week. */
    private static final Duration H2H_KEEP = Duration.ofDays(7);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,5}");
    private static final Set<String> STATUSES = Set.of("FINISHED", "SCHEDULED");

    private final GoalStats goal;
    private final StatsRefreshService refresh;
    private final FootballTables tables;

    public FootballStatsController(GoalStats goal, StatsRefreshService refresh, FootballTables tables) {
        this.goal = goal;
        this.refresh = refresh;
        this.tables = tables;
    }

    // ---- Refresh Stats (background) ----

    @PostMapping("/stats/refresh")
    public StatsRefreshService.Status startRefresh() {
        refresh.start();
        return refresh.status();
    }

    @GetMapping("/stats/refresh")
    public StatsRefreshService.Status refreshStatus() {
        return refresh.status();
    }

    // ---- Data for the match page ----

    /** A team's recent results, from the saved competition results (no calls). */
    @GetMapping("/data/teams/{id}/matches")
    public ResponseEntity<String> teamMatches(@PathVariable long id) {
        return tables.teamResults(id).map(Json::ok).orElseGet(FootballStatsController::notSaved);
    }

    /** A team's squad: every player and his position (saved by Refresh Stats; no calls). */
    @GetMapping("/data/teams/{id}/squad")
    public ResponseEntity<String> teamSquad(@PathVariable long id) {
        return tables.squadJson(id).map(Json::ok).orElseGet(FootballStatsController::notSaved);
    }

    /** A competition's fixtures (SCHEDULED) or results (FINISHED), e.g. CL. */
    @GetMapping("/data/competitions/{code}/matches")
    public ResponseEntity<String> competitionMatches(@PathVariable String code,
                                                     @RequestParam(defaultValue = "SCHEDULED") String status) {
        checkCode(code);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be FINISHED or SCHEDULED");
        }
        return tables.matchesJson(code, status).map(Json::ok).orElseGet(FootballStatsController::notSaved);
    }

    /** League table, e.g. PL, BL1, CL: GOAL API's saved table, else worked out from the saved results (no call). */
    @GetMapping("/data/competitions/{code}/standings")
    public ResponseEntity<String> standings(@PathVariable String code) {
        checkCode(code);
        return tables.standingsJson(code).map(Json::ok).orElseGet(FootballStatsController::notSaved);
    }

    /** Top scorers in a competition (goals, assists, penalties, games played). */
    @GetMapping("/data/competitions/{code}/scorers")
    public ResponseEntity<String> scorers(@PathVariable String code) {
        checkCode(code);
        return tables.scorersJson(code).map(Json::ok).orElseGet(() -> Json.ok("{\"scorers\":[]}"));
    }

    /**
     * Previous meetings of the two teams in a saved fixture: 1 GOAL API call the first time,
     * then the saved copy for a week (an old copy is used if the API refuses).
     */
    @GetMapping("/data/matches/{id}/head2head")
    public ResponseEntity<String> headToHead(@PathVariable long id) {
        Optional<Instant> saved = tables.headToHeadSavedAt(id);
        if (saved.isPresent() && saved.get().isAfter(Instant.now().minus(H2H_KEEP))) {
            return Json.ok(tables.headToHeadJson(id).orElseThrow());
        }
        try {
            if (goal.configured() && goal.saveHeadToHead(id)) {
                return Json.ok(tables.headToHeadJson(id).orElseThrow());
            }
        } catch (RuntimeException e) {
            if (saved.isEmpty()) {
                return Json.message(503, e.getMessage());
            }
        }
        return saved.isPresent() ? Json.ok(tables.headToHeadJson(id).orElseThrow())
                : Json.ok("{\"aggregates\":{\"numberOfMatches\":0},\"matches\":[]}");
    }

    /** 404 telling the page to click Refresh Stats. */
    private static ResponseEntity<String> notSaved() {
        return Json.response(HttpStatus.NOT_FOUND,
                "{\"message\":\"Refresh Stats\",\"notSaved\":true}");
    }

    private static void checkCode(String code) {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Bad competition code");
        }
    }
}
