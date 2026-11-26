package com.sportsbettingassistant.football.live;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;

/**
 * Live Stats (GOAL API).
 * GET  /api/football/live              ticked games, scores, calls left (no API calls)
 * POST /api/football/live/fixtures     load today's games (1 call per competition, reused 3 hours)
 * POST /api/football/live/track        {"ids":[...]} save the ticked games
 * GET  /api/football/live/match/{id}   one game's latest stats and snapshots (no API calls)
 * POST /api/football/live/replay       load one game once, even a finished one (2 calls):
 *                                      ?tournament=epl&date=2026-11-21&kickoff=...&home=...&away=...
 */
@RestController
@RequestMapping("/api/football/live")
public class LiveStatsController {

    private final LiveService live;

    public LiveStatsController(LiveService live) {
        this.live = live;
    }

    @GetMapping
    public ResponseEntity<String> state() {
        return Json.ok(live.state());
    }

    @PostMapping("/fixtures")
    public ResponseEntity<String> fixtures(@RequestParam(defaultValue = "false") boolean force) {
        return Json.ok(live.loadFixtures(force));
    }

    @PostMapping("/track")
    public ResponseEntity<String> track(@RequestBody String body) {
        List<String> ids = new ArrayList<>();
        if (Json.parse(body).get("ids") instanceof List<?> l) {
            l.forEach(o -> ids.add(String.valueOf(o)));
        }
        return Json.ok(live.track(ids));
    }

    /** Follow (on=true) or stop following a game from the match page; it's ticked on match day. */
    @PostMapping("/follow")
    public ResponseEntity<String> follow(@RequestParam String tournament, @RequestParam String kickoff,
                                         @RequestParam String home, @RequestParam String away,
                                         @RequestParam(defaultValue = "true") boolean on) {
        try {
            return Json.ok(on ? live.follow(tournament, kickoff, home, away) : live.unfollow(tournament, kickoff, home, away));
        } catch (IllegalArgumentException e) {
            return Json.message(404, e.getMessage());
        }
    }

    /** Post-Match for many finished games at once: body {"games": [{tournament, kickoff, home, away}]}. */
    @PostMapping("/post-match")
    public ResponseEntity<String> postMatch(@RequestBody String body) {
        List<java.util.Map<String, Object>> games = Json.maps(Json.parse(body).get("games"));
        return Json.ok(live.postMatch(games));
    }

    @PostMapping("/replay")
    public ResponseEntity<String> replay(@RequestParam String tournament, @RequestParam String date,
                                         @RequestParam(required = false) String kickoff,
                                         @RequestParam String home, @RequestParam String away) {
        try {
            return Json.ok(live.replay(tournament, date, kickoff, home, away));
        } catch (IllegalArgumentException e) {
            return Json.message(404, e.getMessage());
        } catch (RuntimeException e) {
            return Json.message(503, e.getMessage());
        }
    }

    @GetMapping("/match/{id}")
    public ResponseEntity<String> match(@PathVariable String id) {
        return Json.ok(live.match(id));
    }

    /** A finished game's highlights video: {url, title} or {} (1 GOAL API call at most every 6 hours a game). */
    @GetMapping("/match/{id}/highlights")
    public ResponseEntity<String> highlights(@PathVariable String id) {
        return Json.ok(live.highlights(id));
    }
}
