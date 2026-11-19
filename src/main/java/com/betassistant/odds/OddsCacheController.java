package com.betassistant.odds;

import java.util.List;

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

/** Local odds cache: refresh costs credits, reading is free. */
@RestController
@RequestMapping("/api/odds/cache")
public class OddsCacheController {

    private final OddsCacheService cache;

    public OddsCacheController(OddsCacheService cache) {
        this.cache = cache;
    }

    /**
     * Credits left this month on The Odds API. Uses the /sports call, which costs nothing,
     * and reads the quota headers it returns.
     */
    @GetMapping("/quota")
    public java.util.Map<String, Object> quota() {
        ResponseEntity<String> res = cache.quota();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("remaining", res.getHeaders().getFirst("x-requests-remaining"));
        out.put("used", res.getHeaders().getFirst("x-requests-used"));
        out.put("ok", res.getStatusCode().is2xxSuccessful());
        return out;
    }

    @GetMapping
    public List<String> list() {
        return cache.cachedSports();
    }

    /** The configured tournament list. */
    @GetMapping("/tournaments")
    public List<String> tournaments() {
        return cache.tournaments();
    }

    /** Refresh every configured tournament that has fixtures. Costs markets x 1 credit per active tournament. */
    @PostMapping("/refresh-all")
    public ResponseEntity<String> refreshAll(@RequestParam(defaultValue = "h2h,totals") String markets,
                                             @RequestParam(defaultValue = "skybet") String bookmakers,
                                             @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(cache.refreshAll(markets, bookmakers, days));
    }

    @GetMapping("/{sport}")
    public ResponseEntity<String> read(@PathVariable String sport) {
        return cache.read(sport)
                .map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Nothing cached for " + sport + " yet. Refresh it first.\"}"));
    }

    /** Costs markets x 1 credit (bookmakers up to 10 = one region). */
    @PostMapping("/{sport}/refresh")
    public ResponseEntity<String> refresh(@PathVariable String sport,
                                          @RequestParam(defaultValue = "h2h,totals") String markets,
                                          @RequestParam(defaultValue = "skybet") String bookmakers,
                                          @RequestParam(defaultValue = "30") int days) {
        return cache.refresh(sport, markets, bookmakers, days);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
    }
}
