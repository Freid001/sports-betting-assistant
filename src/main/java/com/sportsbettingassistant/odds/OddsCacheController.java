package com.sportsbettingassistant.odds;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;

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

    /** The bookmaker picked in Refresh Odds: {"bookmaker": "skybet"}. */
    @GetMapping("/bookmaker")
    public Map<String, String> bookmaker() {
        return Map.of("bookmaker", cache.bookmaker());
    }

    /** Picks the bookmaker for the whole app (pages and live odds); 400 for one not configured. */
    @PostMapping("/bookmaker")
    public Map<String, String> setBookmaker(@RequestParam String key) {
        return Map.of("bookmaker", cache.setBookmaker(key));
    }

    /** The bookmakers you can pick in Refresh Odds and the extra markets each offers (for the pop-up). */
    @GetMapping("/extra-markets")
    public Map<String, List<String>> extraMarkets() {
        return cache.extraMarkets();
    }

    /** Test: which markets each UK bookmaker has for one match (1 credit). */
    @PostMapping("/{sport}/events/{eventId}/markets")
    public Map<String, Object> availableMarkets(@PathVariable String sport, @PathVariable String eventId,
                                                @RequestParam(defaultValue = "uk") String regions) {
        return cache.availableMarkets(sport, eventId, regions);
    }

    /** In-play prices for one match now (1 credit per market returned), saved under the point in the match. */
    @PostMapping("/{sport}/events/{eventId}/live")
    public Map<String, Object> refreshLive(@PathVariable String sport, @PathVariable String eventId,
                                           @RequestParam(defaultValue = "skybet") String bookmaker,
                                           @RequestParam String point) {
        return cache.refreshLive(sport, eventId, bookmaker, point);
    }

    /**
     * Test: in-play prices for these markets now (comma-separated, e.g. h2h,btts), not saved under any bet window.
     * 1 credit per market returned.
     */
    @PostMapping("/{sport}/events/{eventId}/live-test")
    public Map<String, Object> testLive(@PathVariable String sport, @PathVariable String eventId,
                                        @RequestParam(defaultValue = "skybet") String bookmaker,
                                        @RequestParam String markets) {
        return cache.testLive(sport, eventId, bookmaker, List.of(markets.split(",")));
    }

    /** The in-play prices saved for a match, by point in the match (no API call). */
    @GetMapping("/events/{eventId}/live")
    public Map<String, Object> live(@PathVariable String eventId, @RequestParam(defaultValue = "skybet") String bookmaker) {
        return cache.live(eventId, bookmaker);
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
        return Json.ok(cache.refreshAll(markets, bookmakers, days));
    }

    @GetMapping("/{sport}")
    public ResponseEntity<String> read(@PathVariable String sport) {
        return cache.read(sport).map(Json::ok)
                .orElseGet(() -> Json.message(404, "Nothing cached for " + sport + " yet. Refresh it first."));
    }

    /** Costs markets x 1 credit (bookmakers up to 10 = one region). */
    @PostMapping("/{sport}/refresh")
    public ResponseEntity<String> refresh(@PathVariable String sport,
                                          @RequestParam(defaultValue = "h2h,totals") String markets,
                                          @RequestParam(defaultValue = "skybet") String bookmakers,
                                          @RequestParam(defaultValue = "30") int days) {
        return cache.refresh(sport, markets, bookmakers, days);
    }
}
