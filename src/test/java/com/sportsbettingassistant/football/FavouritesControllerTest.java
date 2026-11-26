package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.json.BasicJsonParser;

import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Favourites: the team list for the pop-up, and saving favourites so they survive a restart. */
class FavouritesControllerTest {

    @TempDir
    Path tmp;
    Store store;
    FootballTables tables;

    @AfterEach
    void closeStore() {
        store.close();
    }

    @BeforeEach
    void savedResults() {
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        matches("PL", "FINISHED", team("Tottenham Hotspur FC", "Tottenham", "TOT"), team("Ipswich Town FC", "Ipswich Town", "IPS"));
        // Spurs also play in the Champions League: listed once, under the Premier League
        matches("CL", "FINISHED", team("Tottenham Hotspur FC", "Tottenham", "TOT"), team("FC Bayern München", "Bayern", "FCB"));
        matches("EC", "FINISHED", team("England", "England", "ENG"), team("Spain", "Spain", "ESP"));
    }

    private static String team(String name, String shortName, String tla) {
        return "{\"id\":" + Math.abs(name.hashCode()) + ",\"name\":\"" + name + "\",\"shortName\":\"" + shortName + "\",\"tla\":\"" + tla
                + "\",\"crest\":\"https://crests.example/" + tla + ".png\"}";
    }

    private int matchId = 1;

    /** Saves results as Refresh Stats would. */
    private void matches(String code, String status, String home, String away) {
        saved(code, status, "{\"matches\":[{\"id\":" + (matchId++) + ",\"status\":\"" + status + "\",\"homeTeam\":"
                + home + ",\"awayTeam\":" + away + "}]}");
    }

    private void saved(String code, String status, String json) {
        tables.saveMatches(code, status, json);
    }

    private FavouritesController controller() {
        return new FavouritesController(store, tables);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> groups(String json) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (Object g : (List<Object>) new BasicJsonParser().parseMap(json).get("groups")) {
            Map<String, Object> group = (Map<String, Object>) g;
            out.put((String) group.get("label"), ((List<Map<String, Object>>) group.get("teams")).stream()
                    .map(t -> (String) t.get("name")).toList());
        }
        return out;
    }

    @Test
    void clubsAreGroupedByLeagueAndListedOnce() {
        Map<String, List<String>> g = groups(controller().options().getBody());
        assertThat(g.get("Premier League")).containsExactly("Ipswich Town FC", "Tottenham Hotspur FC");
        assertThat(g.get("Other Champions League clubs")).containsExactly("FC Bayern München");
    }

    @Test
    void nationalTeamsIncludeOnesNotInAnySavedTournament() {
        List<String> nations = groups(controller().options().getBody()).get("International");
        assertThat(nations).contains("England", "Spain", "Wales", "Scotland");
        assertThat(nations).doesNotHaveDuplicates();
    }

    @Test
    void teamListUpdatesWhenRefreshStatsSavesNewResults() {
        FavouritesController c = controller();
        assertThat(groups(c.options().getBody()).get("Premier League")).doesNotContain("Sunderland AFC");
        matches("PL", "SCHEDULED", team("Sunderland AFC", "Sunderland", "SUN"), team("Ipswich Town FC", "Ipswich Town", "IPS"));
        assertThat(groups(c.options().getBody()).get("Premier League")).contains("Sunderland AFC");
    }

    @Test
    void favouritesAreSavedAndSurviveARestart() {
        controller().save("{\"teams\":[{\"name\":\"Tottenham Hotspur FC\",\"shortName\":\"Tottenham\",\"tla\":\"TOT\"},"
                + "{\"name\":\"Wales\",\"shortName\":\"Wales\",\"crest\":null},{\"nameless\":true}]}");

        store.close();                                   // restart: reopen the same database file
        store = TempStore.in(tmp);
        tables = new FootballTables(store);
        String after = controller().get().getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> teams = (List<Map<String, Object>>) new BasicJsonParser().parseMap(after).get("teams");
        assertThat(teams).extracting(t -> t.get("name")).containsExactly("Tottenham Hotspur FC", "Wales");
    }

    @Test
    void noFavouritesYetIsAnEmptyList() {
        assertThat(controller().get().getBody()).isEqualTo("{\"teams\":[]}");
    }

    @Test
    void damagedOrOddSavedResultsAreSkipped() {
        saved("ELC", "FINISHED", "{broken");
        saved("BL1", "FINISHED", "{\"matches\":[\"x\",{\"homeTeam\":{\"shortName\":\"No name\"},\"awayTeam\":\"x\"}]}");
        saved("SA", "FINISHED", "{\"matches\":\"none\"}");
        Map<String, List<String>> g = groups(controller().options().getBody());
        assertThat(g).doesNotContainKeys("Championship", "Bundesliga", "Serie A");
        assertThat(g).containsKey("Premier League");
    }

    @Test
    void savingWithoutATeamsListSavesNone() {
        assertThat(controller().save("{}").getBody()).isEqualTo("{\"teams\":[]}");
        assertThat(controller().get().getBody()).isEqualTo("{\"teams\":[]}");
    }

}
