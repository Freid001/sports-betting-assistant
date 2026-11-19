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
 * GET  /api/football/live/match/{id}   one game's latest stats (no API calls)
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

    @GetMapping("/match/{id}")
    public ResponseEntity<String> match(@PathVariable String id) {
        return Json.ok(live.match(id));
    }
}
