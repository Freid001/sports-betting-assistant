package com.betassistant.football;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Match-page stats from football-data.org. Everything except head to head is saved by
 * "Refresh stats" (StatsRefreshService); these endpoints only read saved copies.
 * Head to head is one call the first time a match page needs it.
 */
@RestController
@RequestMapping("/api/football")
public class FootballStatsController {

    /** Head to head is fetched when a match page first needs it, then reused for a week. */
    private static final Duration H2H_KEEP = Duration.ofDays(7);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,5}");
    private static final Set<String> STATUSES = Set.of("FINISHED", "SCHEDULED");

    private final FootballDataClient client;
    private final StatsRefreshService refresh;

    public FootballStatsController(FootballDataClient client, StatsRefreshService refresh) {
        this.client = client;
        this.refresh = refresh;
    }

    // ---- Refresh stats (background) ----

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

    /** A team's recent results, built from the saved competition results (falls back to one API call). */
    @GetMapping("/data/teams/{id}/matches")
    public ResponseEntity<String> teamMatches(@PathVariable long id) {
        return refresh.teamResults(id)
                .map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> client.cached("/teams/" + id + "/matches",
                        FootballDataClient.params("status", "FINISHED", "limit", "30"))
                        .map(Json::ok).orElseGet(Json::notSaved));
    }

    /** A competition's fixtures (SCHEDULED) or results (FINISHED), e.g. CL. */
    @GetMapping("/data/competitions/{code}/matches")
    public ResponseEntity<String> competitionMatches(@PathVariable String code,
                                                     @RequestParam(defaultValue = "SCHEDULED") String status) {
        checkCode(code);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be FINISHED or SCHEDULED");
        }
        return client.cached("/competitions/" + code + "/matches", FootballDataClient.params("status", status))
                .map(Json::ok).orElseGet(Json::notSaved);
    }

    /** League table, e.g. PL, BL1, CL: worked out from the saved season results (no call). */
    @GetMapping("/data/competitions/{code}/standings")
    public ResponseEntity<String> standings(@PathVariable String code) {
        checkCode(code);
        return refresh.standings(code).map(Json::ok).orElseGet(Json::notSaved);
    }

    /** Top scorers in a competition (goals, assists, penalties, games played). */
    @GetMapping("/data/competitions/{code}/scorers")
    public ResponseEntity<String> scorers(@PathVariable String code) {
        checkCode(code);
        return client.cached("/competitions/" + code + "/scorers", StatsRefreshService.SCORERS)
                .map(Json::ok).orElseGet(() -> Json.ok("{\"scorers\":[]}"));
    }

    /** Previous meetings of the two teams in a football-data.org match. */
    @GetMapping("/data/matches/{id}/head2head")
    public ResponseEntity<String> headToHead(@PathVariable long id) {
        return client.get("/matches/" + id + "/head2head", StatsRefreshService.H2H, H2H_KEEP);
    }

    /** Small response helpers. */
    static final class Json {
        static ResponseEntity<String> ok(String json) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        }

        static ResponseEntity<String> notSaved() {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"Not saved yet. Click Refresh stats on the Football page.\",\"notSaved\":true}");
        }
    }

    private static void checkCode(String code) {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Bad competition code");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
    }
}
