package com.sportsbettingassistant.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.odds.OddsApiClient;

/**
 * GET  /settings                          the Settings page
 * GET  /api/settings                      keys (for the page's masked boxes), theme and the themes to pick from
 * POST /api/settings/keys/{name}          {"key":"..."} saves a key ("" removes it)
 * POST /api/settings/keys/{name}/check    tries the key: The Odds API's sports list (free), GOAL API (1 call)
 * POST /api/settings/theme?key=black      picks a theme
 * POST /api/settings/stake-bar?on=false   shows or hides the Stake Bar (the top bar's £ stake buttons)
 * POST /api/settings/animations?on=false  the match page's banners (GOAL!, cards, kick-off / HT / FT...) on or off
 * POST /api/settings/sounds?on=false      the match page's sounds (crowd, whistles, chime) on or off
 * POST /api/settings/refresh              {"matchResult":true,"scope":"favs","markets":["btts"]}: what Refresh Odds fetches
 * POST /api/settings/live-refresh?minutes=2   how often followed games are refreshed (1, 2, 5 or 10)
 * POST /api/settings/live-odds?on=false   in-play odds at each timeline point of a followed game, or not
 */
@Controller
public class SettingsController {

    private final SettingsService settings;
    private final OddsApiClient odds;
    private final GoalApiClient goal;

    public SettingsController(SettingsService settings, OddsApiClient odds, GoalApiClient goal) {
        this.settings = settings;
        this.odds = odds;
        this.goal = goal;
    }

    @GetMapping("/settings")
    public String page() {
        return "settings";
    }

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<String> get() {
        List<Map<String, Object>> keys = new ArrayList<>();
        SettingsService.KEYS.forEach(k -> keys.add(settings.keyStatus(k.name())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", keys);
        out.put("ready", settings.ready());
        out.put("theme", settings.theme().key());
        out.put("stakeBar", settings.stakeBar());
        out.put("animations", settings.animations());
        out.put("sounds", settings.sounds());
        out.put("refresh", settings.refreshOptions());
        out.put("liveRefresh", settings.liveRefreshMinutes());
        out.put("liveOdds", settings.liveOdds());
        List<Map<String, Object>> themes = new ArrayList<>();
        SettingsService.THEMES.forEach(t -> themes.add(t.toMap()));   // as maps: Json.write doesn't write records
        out.put("themes", themes);
        return Json.ok(out);
    }

    @PostMapping("/api/settings/keys/{name}")
    @ResponseBody
    public ResponseEntity<String> saveKey(@PathVariable String name, @RequestBody String body) {
        settings.setApiKey(name, Json.text(Json.parse(body).get("key")));
        Map<String, Object> out = new LinkedHashMap<>(settings.keyStatus(name));
        out.put("ready", settings.ready());
        return Json.ok(out);
    }

    @PostMapping("/api/settings/keys/{name}/check")
    @ResponseBody
    public ResponseEntity<String> checkKey(@PathVariable String name) {
        SettingsService.find(name);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if ("odds-api".equals(name)) {
                ResponseEntity<String> res = odds.get("/sports", Map.of());
                out.put("ok", res.getStatusCode().is2xxSuccessful());
                out.put("message", res.getStatusCode().is2xxSuccessful() ? "Key works." : message(res.getBody()));
                out.put("left", res.getHeaders().getFirst("x-requests-remaining"));
            } else {
                goal.getJson("/leagues", Map.of("limit", "1"));
                out.put("ok", true);
                out.put("message", "Key works.");
                out.put("left", goal.usage().get("remaining"));
            }
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
        }
        return Json.ok(out);
    }

    @PostMapping("/api/settings/theme")
    @ResponseBody
    public ResponseEntity<String> theme(@RequestParam String key) {
        settings.setTheme(key);
        return Json.ok(Map.of("theme", key));
    }

    @PostMapping("/api/settings/animations")
    @ResponseBody
    public ResponseEntity<String> animations(@RequestParam boolean on) {
        settings.setAnimations(on);
        return Json.ok(Map.of("animations", on));
    }

    @PostMapping("/api/settings/sounds")
    @ResponseBody
    public ResponseEntity<String> sounds(@RequestParam boolean on) {
        settings.setSounds(on);
        return Json.ok(Map.of("sounds", on));
    }

    @PostMapping("/api/settings/stake-bar")
    @ResponseBody
    public ResponseEntity<String> stakeBar(@RequestParam boolean on) {
        settings.setStakeBar(on);
        return Json.ok(Map.of("stakeBar", on));
    }

    @PostMapping("/api/settings/live-refresh")
    @ResponseBody
    public ResponseEntity<String> liveRefresh(@RequestParam int minutes) {
        settings.setLiveRefreshMinutes(minutes);
        return Json.ok(Map.of("liveRefresh", minutes));
    }

    @PostMapping("/api/settings/live-odds")
    @ResponseBody
    public ResponseEntity<String> liveOdds(@RequestParam boolean on) {
        settings.setLiveOdds(on);
        return Json.ok(Map.of("liveOdds", on));
    }

    @PostMapping("/api/settings/refresh")
    @ResponseBody
    public ResponseEntity<String> refresh(@RequestBody String body) {
        Map<String, Object> in = Json.parse(body);
        List<String> markets = new ArrayList<>();
        if (in.get("markets") instanceof List<?> list) {
            list.forEach(m -> markets.add(String.valueOf(m)));
        }
        settings.setRefreshOptions(!Boolean.FALSE.equals(in.get("matchResult")), String.valueOf(in.get("scope")), markets);
        return Json.ok(settings.refreshOptions());
    }

    private static String message(String body) {
        String m = body == null ? null : Json.text(Json.parse(body).get("message"));
        return m != null ? m : "The API refused the key.";
    }
}
