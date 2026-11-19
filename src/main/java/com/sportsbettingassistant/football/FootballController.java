package com.sportsbettingassistant.football;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.sportsbettingassistant.odds.OddsApiClient;
import com.sportsbettingassistant.odds.OddsCacheService;
import com.sportsbettingassistant.json.Json;

/** Football page: every upcoming match in my tournaments, from the local cache. */
@Controller
public class FootballController {

    private final OddsCacheService cache;
    private final TeamLogoService logos;

    public FootballController(OddsCacheService cache, TeamLogoService logos) {
        this.cache = cache;
        this.logos = logos;
    }

    /** Team crests for a tournament (saved after the first call). Empty list if not available. */
    @GetMapping("/api/football/teams/{tournament}")
    @ResponseBody
    public ResponseEntity<String> teams(@PathVariable String tournament,
                                        @RequestParam(defaultValue = "false") boolean refresh) {
        String body = logos.teams(OddsApiClient.safeId(tournament), refresh).orElse("{\"teams\":[]}");
        return Json.ok(body);
    }

    @GetMapping("/football")
    public String page() {
        return "football";
    }

    /** Match page: our main page for everything about one match. Data is loaded by the page itself. */
    @GetMapping("/football/match/{id}")
    public String match(@PathVariable String id, Model model) {
        model.addAttribute("matchId", OddsApiClient.safeId(id));
        return "football-match";
    }

    /** My tournaments: our name, the API key used to read its cache, and whether it has been cached. */
    @GetMapping("/api/football/tournaments")
    @ResponseBody
    public List<Map<String, Object>> tournaments() {
        List<String> cached = cache.cachedSports();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : cache.tournaments()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", OddsCacheService.footballName(key));
            t.put("key", key);
            t.put("cached", cached.contains(key));
            out.add(t);
        }
        return out;
    }

    /**
     * "Also fetch all markets": for every saved match kicking off within {@code days}, one request for the extra
     * markets ticked in the pop-up (BTTS, goals lines, corners, cards, player shots...), comma-separated; all of
     * odds-api.extra-markets if none given. {@code ids} limits it to those matches (the pop-up's "Favourites'
     * matches"); all matches if not given. 1 credit per market the bookmaker returns per match.
     */
    @PostMapping("/api/football/refresh-markets")
    @ResponseBody
    public ResponseEntity<String> refreshMarkets(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(defaultValue = "skybet") String bookmaker,
                                                 @RequestParam(required = false) String markets,
                                                 @RequestParam(required = false) String ids) {
        List<String> picked = markets == null || markets.isBlank() ? cache.extraMarkets(bookmaker)
                : List.of(markets.replaceAll("\\s", "").split(","));
        java.util.Set<String> only = ids == null ? null : ids.isBlank() ? java.util.Set.of() : new java.util.HashSet<>(List.of(ids.replaceAll("\\s", "").split(",")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sport : cache.cachedSports()) {
            if (cache.tournaments().contains(sport) && cache.hasFixtures(sport)) {
                out.add(cache.refreshExtras(sport, days, bookmaker, picked, only));
            }
        }
        return Json.ok(out);
    }

    /** Refresh all my tournaments (inactive ones are skipped for free). */
    @PostMapping("/api/football/refresh")
    @ResponseBody
    public ResponseEntity<String> refresh(@RequestParam(defaultValue = "h2h,totals") String markets,
                                          @RequestParam(defaultValue = "skybet") String bookmakers,
                                          @RequestParam(defaultValue = "30") int days) {
        return Json.ok(cache.refreshAll(markets, bookmakers, days));
    }
}
