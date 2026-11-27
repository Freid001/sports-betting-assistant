package com.sportsbettingassistant.predictions;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;

/**
 * POST /api/predictions   saves a prediction the page worked out (before kick-off, or at a bet window)
 * GET  /api/predictions   every saved prediction with its result once the game's finished (the accuracy report)
 */
@RestController
public class PredictionController {

    private final PredictionLog log;

    public PredictionController(PredictionLog log) {
        this.log = log;
    }

    @PostMapping("/api/predictions")
    public ResponseEntity<String> save(@RequestBody String body) {
        boolean saved = log.save(Json.parse(body), Instant.now());
        return Json.ok(Map.of("saved", saved));
    }

    @GetMapping("/api/predictions")
    public ResponseEntity<String> all() {
        return Json.ok(Map.of("predictions", log.all()));
    }
}
