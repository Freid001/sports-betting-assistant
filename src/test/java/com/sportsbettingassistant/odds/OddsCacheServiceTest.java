package com.sportsbettingassistant.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Saving odds to files, reading them back, and refreshing only tournaments that have fixtures. */
class OddsCacheServiceTest {

    private static final String SPORTS = "[{\"key\":\"soccer_epl\"},{\"key\":\"soccer_uefa_champs_league\"}]";
    private static final String EVENTS = "[{\"id\":\"e1\",\"commence_time\":\"2026-11-21T15:00:00Z\"},"
            + "{\"id\":\"e2\",\"commence_time\":\"2026-11-21T17:30:00Z\"}]";

    @TempDir
    Path tmp;
    FakeServer api;
    Store store;
    OddsCacheService cache;

    @BeforeEach
    void setUp() {
        api = new FakeServer();
        store = TempStore.in(tmp);
        MockEnvironment env = new MockEnvironment()
                .withProperty("football.tournaments[0]", "epl")
                .withProperty("football.tournaments[1]", " uefa_champs_league ")
                .withProperty("football.tournaments[2]", "fifa_world_cup");
        cache = new OddsCacheService(new OddsApiClient("k", api.url()), store, env);
    }

    @AfterEach
    void stop() {
        api.close();
        store.close();
    }

    @Test
    void tournamentsComeFromConfigAsApiKeys() {
        assertThat(cache.tournaments()).containsExactly("soccer_epl", "soccer_uefa_champs_league", "soccer_fifa_world_cup");
    }

    @Test
    void refreshSavesTheOddsWithWhenAndWhatWasFetched() throws Exception {
        api.answer("/sports/soccer_epl/odds", 200, EVENTS, Map.of("x-requests-last", "2"));

        ResponseEntity<String> res = cache.refresh("soccer_epl", "h2h, totals", "skybet", 30);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        String saved = cache.read("soccer_epl").orElseThrow();
        assertThat(saved).startsWith("{\"sport\":\"soccer_epl\"").contains("\"markets\":\"h2h,totals\"")
                .contains("\"creditsUsed\":\"2\"").contains("\"id\":\"e1\"").contains("\"id\":\"e2\"");
        assertThat(res.getBody()).isEqualTo(saved);
        assertThat(api.last().query()).contains("markets=h2h,totals").contains("bookmakers=skybet")
                .contains("oddsFormat=decimal").contains("commenceTimeFrom=").contains("commenceTimeTo=");
        assertThat(cache.cachedSports()).containsExactly("soccer_epl");
        assertThat(cache.hasFixtures("soccer_epl")).isTrue();
    }

    @Test
    void aFailedRefreshKeepsTheOldFile() {
        api.answer("/sports/soccer_epl/odds", 429, "{\"message\":\"quota\"}");
        assertThat(cache.refresh("soccer_epl", "h2h", "skybet", 30).getStatusCode().value()).isEqualTo(429);
        assertThat(cache.read("soccer_epl")).isEmpty();
        assertThat(cache.cachedSports()).isEmpty();
    }

    @Test
    void otherSportsAreSavedSeparately() {
        api.answer("/sports/tennis_atp/odds", 200, "[]");
        cache.refresh("tennis_atp", "h2h", "skybet", 7);
        assertThat(cache.read("tennis_atp")).isPresent();
        assertThat(cache.hasFixtures("tennis_atp")).isFalse();
        assertThat(cache.cachedSports()).isEmpty();
    }

    @Test
    void refreshChecksItsInputs() {
        assertThatThrownBy(() -> cache.refresh("../x", "h2h", "skybet", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refresh("soccer_epl", "h2h;drop", "skybet", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refresh("soccer_epl", "", "skybet", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refresh("soccer_epl", "h2h", "skybet", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refresh("soccer_epl", "h2h", "skybet", 61)).isInstanceOf(IllegalArgumentException.class);
        assertThat(api.requests).isEmpty();
    }

    @Test
    void refreshAllSkipsTournamentsWithoutFixturesForFree() {
        api.answer("/sports", 200, SPORTS);
        api.answer("/sports/soccer_epl/odds", 200, EVENTS, Map.of("x-requests-last", "2"));
        api.answer("/sports/soccer_uefa_champs_league/odds", 500, "{}");

        String summary = cache.refreshAll("h2h,totals", "skybet", 30);

        assertThat(summary).contains("{\"sport\":\"soccer_epl\",\"status\":\"refreshed\",\"events\":2,\"credits\":2}")
                .contains("{\"sport\":\"soccer_uefa_champs_league\",\"status\":\"error 500\",\"credits\":0}")
                .contains("{\"sport\":\"soccer_fifa_world_cup\",\"status\":\"inactive\",\"credits\":0}");
        assertThat(api.count("/sports/soccer_fifa_world_cup/odds")).isZero();
    }

    @Test
    void refreshAllStopsIfTheSportsListFails() {
        api.answer("/sports", 401, "{\"message\":\"bad key\"}");
        assertThatThrownBy(() -> cache.refreshAll("h2h", "skybet", 30)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad key");
    }

    @Test
    void quotaUsesTheFreeSportsCall() {
        api.answer("/sports", 200, SPORTS, Map.of("x-requests-remaining", "480"));
        assertThat(cache.quota().getHeaders().getFirst("x-requests-remaining")).isEqualTo("480");
        assertThat(api.last().path()).isEqualTo("/sports");
    }

    @Test
    void creditsDefaultToZeroWhenTheApiDoesNotSay() {
        api.answer("/sports", 200, "[{\"key\":\"soccer_epl\"}]");
        api.answer("/sports/soccer_epl/odds", 200, "[]");
        assertThat(cache.refreshAll("h2h", "skybet", 30))
                .contains("{\"sport\":\"soccer_epl\",\"status\":\"refreshed\",\"events\":0,\"credits\":0}");
    }

    @Test
    void otherSportsCanBeReadBack() {
        api.answer("/sports/tennis_atp/odds", 200, "[]");
        cache.refresh("tennis_atp", "h2h", "skybet", 7);
        assertThat(cache.read("tennis_atp")).isPresent();
        assertThatThrownBy(() -> cache.refresh("soccer_epl", null, "skybet", 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyPriceComesBackInTheSameShapeAndOrder() {
        String events = "[{\"id\":\"e1\",\"sport_key\":\"soccer_epl\",\"sport_title\":\"EPL\",\"commence_time\":\"2026-11-21T15:00:00Z\","
                + "\"home_team\":\"Arsenal\",\"away_team\":\"Chelsea\",\"bookmakers\":[{\"key\":\"skybet\",\"title\":\"Sky Bet\","
                + "\"markets\":[{\"key\":\"h2h\",\"last_update\":\"t1\",\"outcomes\":[{\"name\":\"Arsenal\",\"price\":1.8},"
                + "{\"name\":\"Chelsea\",\"price\":4.5},{\"name\":\"Draw\",\"price\":3.6}]},"
                + "{\"key\":\"totals\",\"last_update\":\"t2\",\"outcomes\":[{\"name\":\"Over\",\"price\":1.9,\"point\":2.5},"
                + "{\"name\":\"Under\",\"price\":1.95,\"point\":2.5}]},"
                + "{\"key\":\"player_shots_on_target\",\"last_update\":\"t3\",\"outcomes\":[{\"name\":\"Over\","
                + "\"description\":\"Bukayo Saka\",\"price\":2.1,\"point\":1}]}]}]}]";
        cache.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h,totals", "skybet", "3", events);

        String json = cache.read("soccer_epl").orElseThrow();
        assertThat(json).contains("\"fetchedAt\":\"2026-11-19T12:00:00Z\"").contains("\"days\":30")
                .contains("\"bookmakers\":[{\"key\":\"skybet\",\"title\":\"Sky Bet\",\"markets\":[{\"key\":\"h2h\",\"last_update\":\"t1\","
                        + "\"outcomes\":[{\"name\":\"Arsenal\",\"price\":1.8},{\"name\":\"Chelsea\",\"price\":4.5},{\"name\":\"Draw\",\"price\":3.6}]}")
                .contains("{\"name\":\"Over\",\"price\":1.9,\"point\":2.5}")
                .contains("{\"name\":\"Over\",\"description\":\"Bukayo Saka\",\"price\":2.1,\"point\":1}")
                .contains("\"home_team\":\"Arsenal\"").contains("\"commence_time\":\"2026-11-21T15:00:00Z\"");

        cache.save("soccer_epl", "2026-11-20T12:00:00Z", 30, "h2h", "skybet", "1", "[]");   // a new refresh replaces it
        assertThat(cache.read("soccer_epl").orElseThrow()).contains("\"events\":[]");
        assertThat(cache.hasFixtures("soccer_epl")).isFalse();
    }


    // ---------------------------------------------------------------- all markets, one request per match

    private static String soon(int hours) {
        return java.time.Instant.now().plus(java.time.Duration.ofHours(hours)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /** Two upcoming matches saved by Refresh Odds, with their match result. */
    private void twoMatchesSaved() {
        cache.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h,totals", "skybet", "2", "["
                + "{\"id\":\"e1\",\"commence_time\":\"" + soon(48) + "\",\"home_team\":\"Arsenal\",\"away_team\":\"Chelsea\","
                + "\"bookmakers\":[{\"key\":\"skybet\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"Arsenal\",\"price\":1.8}]}]}]},"
                + "{\"id\":\"e2\",\"commence_time\":\"" + soon(72) + "\",\"home_team\":\"Spurs\",\"away_team\":\"Ipswich\","
                + "\"bookmakers\":[]}]");
    }

    private static final String E1_MARKETS = "{\"id\":\"e1\",\"bookmakers\":[{\"key\":\"skybet\",\"title\":\"Sky Bet\",\"markets\":["
            + "{\"key\":\"btts\",\"outcomes\":[{\"name\":\"Yes\",\"price\":1.7},{\"name\":\"No\",\"price\":2.1}]},"
            + "{\"key\":\"alternate_totals\",\"outcomes\":[{\"name\":\"Over\",\"price\":1.9,\"point\":2.5},{\"name\":\"Under\",\"price\":1.9,\"point\":2.5}]}]}]}";

    @Test
    void allMarketsAreFetchedPerMatchSavedAndSummed() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, E1_MARKETS, Map.of("x-requests-last", "2", "x-requests-remaining", "478"));
        api.answer("/sports/soccer_epl/events/e2/odds", 200, "{\"id\":\"e2\",\"bookmakers\":[]}", Map.of("x-requests-last", "0"));

        Map<String, Object> summary = cache.refreshExtras("soccer_epl", 30, "skybet");

        assertThat(summary).containsEntry("matches", 2).containsEntry("credits", 2).containsEntry("errors", 0);
        assertThat(summary.get("markets")).isEqualTo(Map.of("alternate_totals", 1, "btts", 1));
        assertThat(api.last().query()).contains("markets=" + String.join(",", cache.extraMarkets("skybet"))).contains("bookmakers=skybet");
        String json = cache.read("soccer_epl").orElseThrow();
        // Match result first, then the extra markets; and when "all markets" were fetched
        assertThat(json.indexOf("\"key\":\"h2h\"")).isLessThan(json.indexOf("\"key\":\"btts\""));
        assertThat(json).contains("{\"name\":\"Over\",\"price\":1.9,\"point\":2.5}").contains("\"extras_fetched_at\":{\"skybet\":\"20");
        assertThat(store.first("SELECT markets FROM odds_extra_fetches WHERE event_id = 'e1' AND bookmaker = 'skybet'",
                rs -> rs.getString(1))).contains("alternate_totals,btts");
    }

    @Test
    void aNormalRefreshOddsKeepsTheExtraMarketsButDropsFinishedMatches() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, E1_MARKETS);
        api.answer("/sports/soccer_epl/events/e2/odds", 200, "{}");
        cache.refreshExtras("soccer_epl", 30, "skybet");

        // Refresh Odds again: e1 has a new match-result price, e2 has started and is no longer listed
        cache.save("soccer_epl", "2026-11-20T12:00:00Z", 30, "h2h,totals", "skybet", "2", "[{\"id\":\"e1\",\"commence_time\":\""
                + soon(24) + "\",\"home_team\":\"Arsenal\",\"away_team\":\"Chelsea\",\"bookmakers\":[{\"key\":\"skybet\",\"markets\":["
                + "{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"Arsenal\",\"price\":2.0}]}]}]}]");

        String json = cache.read("soccer_epl").orElseThrow();
        assertThat(json).contains("\"price\":2.0").doesNotContain("\"price\":1.8")   // match result replaced
                .contains("\"key\":\"btts\"")                                          // extra markets kept
                .doesNotContain("\"id\":\"e2\"");                                       // started match gone
        assertThat(store.first("SELECT COUNT(*) FROM odds_extra_fetches WHERE event_id = 'e2'", rs -> rs.getInt(1))).contains(0);
    }

    @Test
    void allMarketsUsesTheLastCreditsThenStops() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "2"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, E1_MARKETS, Map.of("x-requests-last", "2", "x-requests-remaining", "0"));
        Map<String, Object> summary = cache.refreshExtras("soccer_epl", 30, "skybet");
        assertThat(summary).containsEntry("matches", 1).containsEntry("credits", 2);   // the last 2 credits are used
        assertThat(String.valueOf(summary.get("stopped"))).contains("no credits left");
        assertThat(api.count("/sports/soccer_epl/events/e2/odds")).isZero();            // then it stops
    }

    @Test
    void allMarketsReportsFailedMatchesAndStopsOnARateLimit() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 422, "{\"message\":\"Invalid market\"}");
        api.answer("/sports/soccer_epl/events/e2/odds", 429, "{\"message\":\"slow down\"}");
        Map<String, Object> summary = cache.refreshExtras("soccer_epl", 30, "skybet");
        assertThat(summary).containsEntry("errors", 1).containsEntry("lastError", "HTTP 422: Invalid market");
        assertThat(String.valueOf(summary.get("stopped"))).contains("429");
    }

    @Test
    void allMarketsOnlyAsksForMatchesWithinTheDaysGivenAndChecksItsInput() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, "{}");
        assertThat(cache.refreshExtras("soccer_epl", 2, "skybet")).containsEntry("matches", 1);   // e2 is 3 days away
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 0, "skybet")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 7, "sky;bet")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extraMarketsComeFromTheSettingsPerBookmaker() {
        OddsCacheService custom = new OddsCacheService(new OddsApiClient("k", api.url()), store,
                new MockEnvironment().withProperty("odds-api.bookmakers.betway", " btts, alternate_totals_corners "));
        assertThat(custom.extraMarkets()).containsOnlyKeys("betway");
        assertThat(custom.extraMarkets("betway")).containsExactly("btts", "alternate_totals_corners");
        assertThatThrownBy(() -> custom.extraMarkets("skybet")).hasMessageContaining("betway");
        // Without settings: Sky Bet and what it offers through The Odds API
        assertThat(cache.extraMarkets()).containsOnlyKeys("skybet");
        assertThat(cache.extraMarkets("skybet")).contains("btts", "alternate_totals").doesNotContain("alternate_totals_corners");
    }

    @Test
    void marketsTheBookmakerDoesntOfferAreRefused() {
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 7, "skybet", java.util.List.of("alternate_totals_corners")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 7, "betfred_uk")).hasMessageContaining("skybet");
    }

    @Test
    void eachBookmakersPricesAreKeptSeparately() {
        OddsCacheService two = new OddsCacheService(new OddsApiClient("k", api.url()), store, new MockEnvironment()
                .withProperty("odds-api.bookmakers.skybet", "btts").withProperty("odds-api.bookmakers.betway", "btts,alternate_totals_corners"));
        String match = "{\"id\":\"e1\",\"commence_time\":\"" + soon(48) + "\",\"home_team\":\"Arsenal\",\"away_team\":\"Chelsea\",\"bookmakers\":[";
        two.save("soccer_epl", "2026-11-19T12:00:00Z", 30, "h2h", "skybet", "1", "[" + match
                + "{\"key\":\"skybet\",\"title\":\"Sky Bet\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"Arsenal\",\"price\":1.8}]}]}]}]");
        two.save("soccer_epl", "2026-11-19T12:05:00Z", 30, "h2h", "betway", "1", "[" + match
                + "{\"key\":\"betway\",\"title\":\"Betway\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"Arsenal\",\"price\":1.85}]}]}]}]");
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, "{\"bookmakers\":[{\"key\":\"betway\",\"title\":\"Betway\",\"markets\":["
                + "{\"key\":\"alternate_totals_corners\",\"outcomes\":[{\"name\":\"Over\",\"price\":1.9,\"point\":9.5}]}]}]}");
        assertThat(two.refreshExtras("soccer_epl", 30, "betway", java.util.List.of("alternate_totals_corners"))).containsEntry("bookmaker", "betway");
        assertThat(api.last().query()).contains("bookmakers=betway");

        String json = two.read("soccer_epl").orElseThrow();
        assertThat(json).contains("\"key\":\"skybet\"").contains("\"price\":1.8}")    // Betway's refresh kept Sky Bet's price
                .contains("\"key\":\"betway\"").contains("\"price\":1.85").contains("\"point\":9.5")
                .contains("\"extras_fetched_at\":{\"betway\":\"20");
    }

    @Test
    void onlyTheTickedMarketsAreFetchedAndReplaced() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e1/odds", 200, E1_MARKETS);
        api.answer("/sports/soccer_epl/events/e2/odds", 200, "{}");
        cache.refreshExtras("soccer_epl", 30, "skybet");   // BTTS and goals lines saved

        // Now only BTTS is ticked, with a new price: goals lines from before are kept
        api.answer("/sports/soccer_epl/events/e1/odds", 200, "{\"bookmakers\":[{\"key\":\"skybet\",\"markets\":["
                + "{\"key\":\"btts\",\"outcomes\":[{\"name\":\"Yes\",\"price\":1.5}]}]}]}");
        Map<String, Object> summary = cache.refreshExtras("soccer_epl", 30, "skybet", java.util.List.of("btts"));

        assertThat(api.last().query()).contains("markets=btts&");
        assertThat(summary.get("markets")).isEqualTo(Map.of("btts", 1));
        String json = cache.read("soccer_epl").orElseThrow();
        assertThat(json).contains("\"price\":1.5").doesNotContain("\"price\":1.7").contains("\"key\":\"alternate_totals\"");
    }

    @Test
    void tickedMarketsMustBeExtraMarkets() {
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 7, "skybet", java.util.List.of("h2h_lay")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("btts");
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 7, "skybet", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extraMarketsCanBeLimitedToSomeMatches() {
        twoMatchesSaved();
        api.answer("/sports", 200, "[]", Map.of("x-requests-remaining", "480"));
        api.answer("/sports/soccer_epl/events/e2/odds", 200, "{}");
        Map<String, Object> summary = cache.refreshExtras("soccer_epl", 30, "skybet", java.util.List.of("btts"), java.util.Set.of("e2", "elsewhere"));
        assertThat(summary).containsEntry("matches", 1);
        assertThat(api.count("/sports/soccer_epl/events/e1/odds")).isZero();
        assertThat(cache.refreshExtras("soccer_epl", 30, "skybet", java.util.List.of("btts"), java.util.Set.of())).containsEntry("matches", 0);
        assertThatThrownBy(() -> cache.refreshExtras("soccer_epl", 30, "skybet", java.util.List.of("btts"), java.util.Set.of("../x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableMarketsListsEachBookmakersMarkets() {
        api.answer("/sports/soccer_epl/events/e1/markets", 200, "{\"id\":\"e1\",\"home_team\":\"Arsenal\",\"away_team\":\"Chelsea\","
                + "\"bookmakers\":[{\"key\":\"skybet\",\"markets\":[{\"key\":\"h2h\"}]},"
                + "{\"key\":\"betfred_uk\",\"markets\":[{\"key\":\"totals\"},{\"key\":\"btts\"},{\"key\":\"h2h\"}]}]}",
                Map.of("x-requests-last", "1"));
        Map<String, Object> out = cache.availableMarkets("soccer_epl", "e1", "uk");
        assertThat(out).containsEntry("home", "Arsenal").containsEntry("credits", 1);
        assertThat(out.get("bookmakers")).isEqualTo(Map.of("skybet", java.util.List.of("h2h"),
                "betfred_uk", java.util.List.of("btts", "h2h", "totals")));
        assertThat(api.last().query()).startsWith("regions=uk&");
        api.answer("/sports/soccer_epl/events/gone/markets", 404, "{\"message\":\"Event not found\"}");
        assertThatThrownBy(() -> cache.availableMarkets("soccer_epl", "gone", "uk")).hasMessageContaining("404: Event not found");
    }

    // ---------------------------------------------------------------- in-play prices

    private static final String LIVE = "{\"id\":\"e1\",\"bookmakers\":[{\"key\":\"skybet\",\"markets\":["
            + "{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"Tottenham Hotspur\",\"price\":1.36},{\"name\":\"Draw\",\"price\":4.5},{\"name\":\"Ipswich Town\",\"price\":11}]},"
            + "{\"key\":\"alternate_totals\",\"outcomes\":[{\"name\":\"Over\",\"price\":1.5,\"point\":1.5}]}]}]}";

    @Test
    void livePricesAreFetchedForTheResultAndTheBookmakersGoalsMarketsAndKeptApartByPoint() {
        api.answer("/sports/soccer_epl/events/e1/odds", 200, LIVE, Map.of("x-requests-last", "3"));

        Map<String, Object> live = cache.refreshLive("soccer_epl", "e1", "skybet", "HT");

        assertThat(api.last().query()).contains("markets=h2h,alternate_totals,btts").contains("bookmakers=skybet");
        assertThat(live).containsEntry("credits", 3).containsEntry("eventId", "e1");
        assertThat(String.valueOf(live.get("markets"))).contains("Ipswich Town").contains("point=1.5");
        assertThat(live).containsEntry("point", "HT");
        cache.refreshLive("soccer_epl", "e1", "skybet", "60");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> points = (Map<String, Map<String, Object>>) cache.live("e1", "skybet").get("points");
        assertThat(points).containsOnlyKeys("HT", "60");
        assertThat(points.get("HT").get("fetchedAt")).isEqualTo(live.get("fetchedAt"));
        assertThat(String.valueOf(points.get("HT").get("markets"))).contains("Tottenham Hotspur").contains("1.36");
        assertThat((Map<?, ?>) cache.live("e2", "skybet").get("points")).isEmpty();
        assertThat(store.first("SELECT COUNT(*) FROM odds_prices", rs -> rs.getInt(1)).orElse(0)).isZero();
    }

    @Test
    void anEmptyLiveAnswerKeepsTheOddsAlreadySavedForThatPoint() {
        api.answer("/sports/soccer_epl/events/e1/odds", 200, LIVE, Map.of("x-requests-last", "2"));
        cache.refreshLive("soccer_epl", "e1", "skybet", "80");
        api.answer("/sports/soccer_epl/events/e1/odds", 200, "{\"id\":\"e1\",\"bookmakers\":[]}", Map.of("x-requests-last", "0"));
        assertThatThrownBy(() -> cache.refreshLive("soccer_epl", "e1", "skybet", "80"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no in-play odds");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> points = (Map<String, Map<String, Object>>) cache.live("e1", "skybet").get("points");
        assertThat(String.valueOf(points.get("80").get("markets"))).contains("Tottenham Hotspur");
    }

    @Test
    void aRefusedLiveFetchSaysWhy() {
        api.answer("/sports/soccer_epl/events/e1/odds", 404, "{\"message\":\"Event not found\"}");
        assertThatThrownBy(() -> cache.refreshLive("soccer_epl", "e1", "skybet", "HT"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("404").hasMessageContaining("Event not found");
        assertThatThrownBy(() -> cache.refreshLive("soccer_epl", "e1", "nobody", "HT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.refreshLive("soccer_epl", "e1", "skybet", "FT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGameFromGoalApiIsFoundInTheSavedOddsByKickOffAndLooseNames() {
        cache.save("soccer_epl", "2026-11-20T12:00:00Z", 30, "h2h", "skybet", "1", "[{\"id\":\"e1\",\"commence_time\":"
                + "\"2026-11-21T15:00:00Z\",\"home_team\":\"Tottenham Hotspur\",\"away_team\":\"Ipswich Town\",\"bookmakers\":[]}]");
        assertThat(cache.findEvent("2026-11-21T15:00:00Z", "Tottenham", "Ipswich"))
                .contains(Map.of("sport", "soccer_epl", "id", "e1"));
        assertThat(cache.findEvent("2026-11-21T18:00:00Z", "Tottenham", "Ipswich")).isEmpty();   // different kick-off
        assertThat(cache.findEvent("2026-11-21T15:00:00Z", "Chelsea", "Leeds")).isEmpty();
    }

    @Test
    void aSavedTimelinePointFetchesThatPointsOdds() {
        cache.save("soccer_epl", "2026-11-20T12:00:00Z", 30, "h2h", "skybet", "1", "[{\"id\":\"e1\",\"commence_time\":"
                + "\"2026-11-21T15:00:00Z\",\"home_team\":\"Tottenham Hotspur\",\"away_team\":\"Ipswich Town\",\"bookmakers\":[]}]");
        api.answer("/sports/soccer_epl/events/e1/odds", 200, LIVE, Map.of("x-requests-last", "3", "x-requests-remaining", "400"));
        LiveOddsAtPoints atPoints = new LiveOddsAtPoints(cache);   // Sky Bet: nothing else picked
        var p = new com.sportsbettingassistant.football.live.LiveService.PointSaved("g1", "30", "2026-11-21T15:00:00Z", "Tottenham", "Ipswich");

        assertThat(atPoints.fetch(p)).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> points = (Map<String, Object>) cache.live("e1", "skybet").get("points");
        assertThat(points).containsOnlyKeys("30");

        var unknown = new com.sportsbettingassistant.football.live.LiveService.PointSaved("g2", "30", "2026-11-21T15:00:00Z", "Chelsea", "Leeds");
        assertThat(atPoints.fetch(unknown)).isFalse();   // not in the saved odds: no call
    }

    @Test
    void eachPointFetchesExactlyWhatIsTickedInSettings() {
        var settings = new com.sportsbettingassistant.settings.SettingsService(store, null);
        LiveOddsAtPoints atPoints = new LiveOddsAtPoints(cache);
        assertThat(atPoints.marketsFor(null)).isNull();   // no Settings: the usual markets
        atPoints.setSettings(settings);
        var at60 = new com.sportsbettingassistant.football.live.LiveService.PointSaved("g1", "60", "k", "A", "B", "1-2");
        assertThat(atPoints.marketsFor(at60)).containsExactly("h2h");   // Home / Draw / Away ticked by default
        settings.setRefreshOptions(true, "favs", java.util.List.of("btts", "player_goal_scorer_anytime"));
        assertThat(atPoints.marketsFor(at60)).containsExactly("h2h", "btts", "player_goal_scorer_anytime");
        settings.setRefreshOptions(true, "all", java.util.List.of("alternate_totals"));   // all goal lines: the main one too
        assertThat(atPoints.marketsFor(at60)).containsExactly("h2h", "alternate_totals", "totals");
        settings.setRefreshOptions(false, "off", java.util.List.of());
        assertThat(atPoints.marketsFor(at60)).isEmpty();
        assertThat(atPoints.fetch(at60)).isFalse();   // nothing ticked: nothing fetched
    }

    @Test
    void theBookmakerPickedInRefreshOddsIsKeptOnTheServerForTheWholeApp() {
        OddsCacheService two = new OddsCacheService(new OddsApiClient("k", api.url()), store, new MockEnvironment()
                .withProperty("odds-api.bookmakers.skybet", "btts").withProperty("odds-api.bookmakers.betway", "btts"));
        assertThat(two.bookmaker()).isEqualTo("skybet");                   // nothing picked yet
        assertThat(two.setBookmaker("betway")).isEqualTo("betway");
        assertThat(two.bookmaker()).isEqualTo("betway");
        assertThatThrownBy(() -> two.setBookmaker("nobody")).isInstanceOf(IllegalArgumentException.class);
        assertThat(two.bookmaker()).isEqualTo("betway");
    }
}
