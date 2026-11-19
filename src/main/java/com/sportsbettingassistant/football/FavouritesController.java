package com.sportsbettingassistant.football;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * Favourite teams, saved in the favourites table so they survive restarts.
 * GET /api/football/favourites           your favourites
 * PUT /api/football/favourites           {"teams":[{name, shortName, tla, crest, type}...]} save them
 * GET /api/football/favourites/options   teams to choose from: clubs from the saved football-data.org
 *                                        results (no API calls) and national teams
 */
@RestController
@RequestMapping("/api/football/favourites")
public class FavouritesController {

    /** Club competitions in the saved results, in the order shown in the pop-up. */
    private static final String[][] CLUB_COMPS = {
        {"PL", "Premier League"}, {"ELC", "Championship"}, {"PD", "La Liga"}, {"BL1", "Bundesliga"},
        {"SA", "Serie A"}, {"FL1", "Ligue 1"}, {"DED", "Eredivisie"}, {"PPL", "Primeira Liga"},
        {"CL", "Other Champions League clubs"}};

    /** National teams always offered, even if they aren't in any saved tournament. */
    private static final String[] NATIONS = {
        "England", "Scotland", "Wales", "Northern Ireland", "Republic of Ireland", "France", "Germany", "Spain",
        "Italy", "Portugal", "Netherlands", "Belgium", "Croatia", "Denmark", "Switzerland", "Austria", "Poland",
        "Sweden", "Norway", "Serbia", "Turkey", "Ukraine", "Czechia", "Hungary", "Greece", "Romania", "Slovakia",
        "Slovenia", "Albania", "Georgia", "Finland", "Iceland", "Israel", "Bosnia and Herzegovina", "Brazil",
        "Argentina", "Uruguay", "Colombia", "USA", "Mexico", "Canada", "Japan", "South Korea", "Australia",
        "Morocco", "Senegal", "Nigeria"};

    private final Store store;
    private final FootballTables tables;

    public FavouritesController(Store store, FootballTables tables) {
        this.store = store;
        this.tables = tables;
    }

    @GetMapping
    public ResponseEntity<String> get() {
        return Json.ok(Map.of("teams", favourites()));
    }

    @PutMapping
    public ResponseEntity<String> save(@RequestBody String body) {
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map<String, Object> t : Json.maps(Json.parse(body).get("teams"))) {
            if (Json.text(t.get("name")) != null) {
                teams.add(team(Json.text(t.get("name")), Json.text(t.get("shortName")), Json.text(t.get("tla")),
                        Json.text(t.get("crest")), Json.text(t.get("type"))));
            }
        }
        store.transaction(() -> {
            store.update("DELETE FROM favourites");
            int position = 0;
            for (Map<String, Object> t : teams) {
                store.update("INSERT OR REPLACE INTO favourites (position, name, short_name, tla, crest, type) VALUES (?, ?, ?, ?, ?, ?)",
                        position++, t.get("name"), t.get("shortName"), t.get("tla"), t.get("crest"), t.get("type"));
            }
        });
        return get();
    }

    /** Teams to choose from, built from the saved results (quick: one query per competition). */
    @GetMapping("/options")
    public ResponseEntity<String> options() {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String[] comp : CLUB_COMPS) {
            List<Map<String, Object>> teams = teamsIn(comp[0], "club", seen);
            if (!teams.isEmpty()) {
                groups.add(group(comp[1], teams));
            }
        }
        List<Map<String, Object>> nations = new ArrayList<>();
        nations.addAll(teamsIn("EC", "international", seen));
        nations.addAll(teamsIn("WC", "international", seen));
        for (String n : NATIONS) {
            if (!seen.containsKey(key(n))) {
                Map<String, Object> t = team(n, n, null, null, "international");
                seen.put(key(n), t);
                nations.add(t);
            }
        }
        nations.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        groups.add(group("International", nations));
        return Json.ok(Map.of("groups", groups));
    }

    private List<Map<String, Object>> favourites() {
        return store.query("SELECT * FROM favourites ORDER BY position", rs -> team(rs.getString("name"),
                rs.getString("short_name"), rs.getString("tla"), rs.getString("crest"), rs.getString("type")));
    }

    /** Teams that played in a competition and aren't already listed under another group. */
    private List<Map<String, Object>> teamsIn(String code, String type, Map<String, Map<String, Object>> seen) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tables.teamsPlayingIn(code)) {
            String name = Json.text(t.get("name"));
            if (name == null || seen.containsKey(key(name))) {
                continue;
            }
            Map<String, Object> team = team(name, Json.text(t.get("shortName")), Json.text(t.get("tla")),
                    Json.text(t.get("crest")), type);
            seen.put(key(name), team);
            out.add(team);
        }
        return out;
    }

    private static Map<String, Object> team(String name, String shortName, String tla, String crest, String type) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("shortName", shortName);
        t.put("tla", tla);
        t.put("crest", crest);
        t.put("type", type);
        return t;
    }

    private static Map<String, Object> group(String label, List<Map<String, Object>> teams) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("label", label);
        g.put("teams", teams);
        return g;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).trim();
    }
}
