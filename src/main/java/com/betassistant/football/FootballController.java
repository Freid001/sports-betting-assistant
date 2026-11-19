package com.betassistant.football;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.betassistant.odds.OddsApiClient;
import com.betassistant.odds.OddsCacheService;

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
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
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

    /** Refresh all my tournaments (inactive ones are skipped for free). */
    @PostMapping("/api/football/refresh")
    @ResponseBody
    public ResponseEntity<String> refresh(@RequestParam(defaultValue = "h2h,totals") String markets,
                                          @RequestParam(defaultValue = "skybet") String bookmakers,
                                          @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(cache.refreshAll(markets, bookmakers, days));
    }
}
