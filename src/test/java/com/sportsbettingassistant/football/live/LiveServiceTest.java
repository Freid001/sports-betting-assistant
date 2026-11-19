package com.sportsbettingassistant.football.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

/** Live Stats: xG estimate, stats layout, and when GOAL API calls are (and aren't) made. */
class LiveServiceTest {

    private static final String LEAGUE = "L1";

    @TempDir
    Path tmp;
    Store store;

    @org.junit.jupiter.api.BeforeEach
    void openStore() {
        store = TempStore.in(tmp);
    }

    @org.junit.jupiter.api.AfterEach
    void closeStore() {
        store.close();
    }

    /** Stands in for GOAL API: records every call and returns canned answers. */
    static class FakeApi extends GoalApiClient {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, Object>> answers = new ConcurrentHashMap<>();
        final java.util.Set<String> failing = ConcurrentHashMap.newKeySet();

        FakeApi() {
            super("test-key", "http://localhost");
        }

        @Override
        public Map<String, Object> getJson(String path, Map<String, String> params) {
            calls.add(path);
            if (failing.contains(path)) {
                throw new IllegalStateException("GOAL API 500: down");
            }
            return answers.getOrDefault(path, Map.of("data", List.of()));
        }

        long callsTo(String part) {
            return calls.stream().filter(c -> c.contains(part)).count();
        }
    }

    private LiveService service(FakeApi api, int maxGames) {
        MockEnvironment env = new MockEnvironment().withProperty("goal-api.leagues.epl", LEAGUE);
        return new LiveService(api, env, store, 5, maxGames, 50);
    }

    /** A fixture as GOAL API sends it (numbers and flags as text). */
    private static Map<String, Object> fixture(String id, Instant kickoff, String status, boolean live) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("leagueId", LEAGUE);
        f.put("matchDate", LocalDate.ofInstant(kickoff, ZoneOffset.UTC).toString());
        f.put("kickoffUtc", kickoff.toString());
        f.put("homeTeamName", "Tottenham");
        f.put("awayTeamName", "Ipswich");
        f.put("homeTeamScore", live ? "1" : "null");
        f.put("awayTeamScore", live ? "0" : "null");
        f.put("matchStatus", status);
        f.put("matchLive", live ? "1" : "0");
        f.put("matchMinute", live ? "20" : "Not Started");
        f.put("matchElapsed", live ? "20" : "null");
        return f;
    }

    private static Map<String, Object> data(Object... fixtures) {
        return Map.of("data", List.of(fixtures));
    }

    private static Map<String, Object> statsBody() {
        List<Map<String, Object>> fullTime = List.of(
                row("Corners", "8", "1"),
                row("Shots Total", "14", "8"),
                row("Shots On Goal", "4", "2"),
                row("Shots Inside Box", "10", "6"),
                row("Shots Outside Box", "4", "2"),
                row("Dangerous Attacks", "82", "33"),
                row("Ball Possession", "56%", "44%"),
                row("Corners", "99", "99"));   // GOAL API repeats Corners; the first one counts
        List<Map<String, Object>> home = List.of(
                player("Son", "3", "1", "0"),
                player("Kane", "4", "3", "1"),
                player("Keeper", "0", "0", "0"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("match", Map.of("fullTime", fullTime));
        d.put("players", Map.of("home", home, "away", List.of()));
        return Map.of("data", d);
    }

    private static Map<String, Object> row(String type, String home, String away) {
        return Map.of("type", type, "home", home, "away", away);
    }

    private static Map<String, Object> player(String name, String shots, String onTarget, String goals) {
        return Map.of("playerName", name, "shotsTotal", shots, "shotsOnGoal", onTarget, "goals", goals);
    }

    private static void waitFor(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }

    // ---------------------------------------------------------------- xG estimate

    @Test
    void xgFromShotCounts() {
        // USA v Haiti: real xG was 1.47 v 0.87
        double[] xg = LiveService.xg(Map.of(
                "Shots Inside Box", new String[] {"11", "6"},
                "Shots Outside Box", new String[] {"3", "2"}));
        assertThat(xg[0]).isCloseTo(11 * 0.12 + 3 * 0.035, within(1e-9));
        assertThat(xg[1]).isCloseTo(6 * 0.12 + 2 * 0.035, within(1e-9));
    }

    @Test
    void penaltiesCountAtPenaltyValueNotAsInsideBoxShots() {
        double[] xg = LiveService.xg(Map.of(
                "Shots Inside Box", new String[] {"14", "10"},
                "Shots Outside Box", new String[] {"12", "2"},
                "Penalty", new String[] {"1", "1"}));
        assertThat(xg[0]).isCloseTo(13 * 0.12 + 12 * 0.035 + 0.76, within(1e-9));
        assertThat(xg[1]).isCloseTo(9 * 0.12 + 2 * 0.035 + 0.76, within(1e-9));
    }

    @Test
    void noXgWithoutShotSplit() {
        assertThat(LiveService.xg(Map.of("Shots Total", new String[] {"5", "3"}))).isNull();
    }

    // ---------------------------------------------------------------- stats layout

    @Test
    @SuppressWarnings("unchecked")
    void statsPutXgFirstAndKeepTheFirstCornersRow() {
        Map<String, Object> s = LiveService.computeStats(statsBody());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("rows");
        assertThat(rows.get(0)).containsEntry("label", "xG (est.)").containsEntry("home", "1.34").containsEntry("away", "0.79");   // 10 x 0.12 + 4 x 0.035
        assertThat(rows).extracting(r -> r.get("label"))
                .containsSubsequence("Shots", "Shots on target", "Dangerous attacks", "Corners", "Possession");
        Map<String, Object> corners = rows.stream().filter(r -> r.get("label").equals("Corners")).findFirst().orElseThrow();
        assertThat(corners).containsEntry("home", "8");
    }

    @Test
    @SuppressWarnings("unchecked")
    void playersWithShotsOnlyMostOnTargetFirst() {
        List<Map<String, Object>> players = (List<Map<String, Object>>) LiveService.computeStats(statsBody()).get("homePlayers");
        assertThat(players).extracting(p -> p.get("name")).containsExactly("Kane", "Son");
        assertThat(players.get(0)).containsEntry("onTarget", 3).containsEntry("goals", 1);
    }

    @Test
    void summaryReadsLiveFlagAndTurnsNullTextIntoNull() {
        Map<String, Object> live = LiveService.summary(fixture("a", Instant.now(), "LIVE", true), "epl");
        assertThat(live).containsEntry("live", true).containsEntry("homeScore", "1").containsEntry("tournament", "epl");
        Map<String, Object> later = LiveService.summary(fixture("b", Instant.now(), "SCHEDULED", false), "epl");
        assertThat(later.get("homeScore")).isNull();
        assertThat(later.get("live")).isEqualTo(false);
    }

    // ---------------------------------------------------------------- when calls are made

    @Test
    void tickingA3pmGameAt7amMakesNoCallsUntilKickOff() {
        FakeApi api = new FakeApi();
        Instant kickoff = Instant.now().plus(Duration.ofHours(8));
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(fixture("g1", kickoff, "SCHEDULED", false)));
        LiveService live = service(api, 20);

        live.loadFixtures(true);
        assertThat(api.calls).containsExactly("/leagues/" + LEAGUE + "/fixtures");   // the pop-up's list: 1 call

        live.track(List.of("g1"));
        live.tick();
        live.tick();
        assertThat(api.calls).hasSize(1);                                             // nothing while waiting
        assertThat(live.state().get("tracked")).isEqualTo(List.of("g1"));
    }

    @Test
    void liveGameGetsScoresAndStatsThenWaitsForTheNextRefresh() throws InterruptedException {
        FakeApi api = new FakeApi();
        Instant kickoff = Instant.now().minus(Duration.ofMinutes(20));
        Map<String, Object> f = fixture("g1", kickoff, "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.answers.put("/fixtures/date/" + f.get("matchDate"), data(f));
        api.answers.put("/fixtures/g1/statistics", statsBody());
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("g1"));                                  // refreshes straight away (in the background)
        waitFor(() -> api.callsTo("/statistics") == 1);
        assertThat(api.callsTo("/fixtures/date/")).isEqualTo(1);    // 1 scores call for the competition
        assertThat(api.callsTo("/statistics")).isEqualTo(1);        // 1 stats call for the game

        int before = api.calls.size();
        live.tick();                                                // within 5 minutes: no new calls
        assertThat(api.calls).hasSize(before);
        assertThat(live.match("g1").get("stats")).isNotNull();
    }

    @Test
    void pausesWhenFewCallsAreLeft() throws InterruptedException {
        FakeApi api = new FakeApi();
        api.restoreUsage(Map.of("remaining", "10", "limit", "1000"));
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(20)), "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("g1"));
        waitFor(() -> live.state().get("warning") != null);
        assertThat(String.valueOf(live.state().get("warning"))).startsWith("Paused");
        assertThat(api.callsTo("/statistics")).isZero();
    }

    @Test
    void trackIgnoresUnknownGamesAndStopsAtTheLimit() {
        FakeApi api = new FakeApi();
        Instant later = Instant.now().plus(Duration.ofHours(5));
        api.answers.put("/leagues/" + LEAGUE + "/fixtures",
                data(fixture("a", later, "SCHEDULED", false), fixture("b", later, "SCHEDULED", false),
                     fixture("c", later, "SCHEDULED", false)));
        LiveService live = service(api, 2);
        live.loadFixtures(true);

        live.track(new ArrayList<>(List.of("nope", "a", "b", "c")));
        assertThat(live.state().get("tracked")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void ticksAreKeptAfterARestart() {
        FakeApi api = new FakeApi();
        api.answers.put("/leagues/" + LEAGUE + "/fixtures",
                data(fixture("g1", Instant.now().plus(Duration.ofHours(5)), "SCHEDULED", false)));
        service(api, 20).loadFixtures(true);
        service(api, 20).track(List.of("g1"));

        LiveService restarted = service(new FakeApi(), 20);
        assertThat(restarted.state().get("tracked")).isEqualTo(List.of("g1"));
    }

    // ---------------------------------------------------------------- more cases

    @Test
    void theGamesListIsReusedForThreeHours() {
        FakeApi api = new FakeApi();
        LiveService live = service(api, 20);
        live.loadFixtures(false);
        live.loadFixtures(false);
        assertThat(api.callsTo("/fixtures")).isEqualTo(1);
        live.loadFixtures(true);                                   // forced: fetched again
        assertThat(api.callsTo("/fixtures")).isEqualTo(2);
    }

    @Test
    void aCompetitionThatFailsToLoadIsNamedInTheWarning() {
        FakeApi api = new FakeApi();
        api.failing.add("/leagues/" + LEAGUE + "/fixtures");
        Map<String, Object> state = service(api, 20).loadFixtures(true);
        assertThat(String.valueOf(state.get("warning"))).isEqualTo("Couldn't load games for: epl");
    }

    @Test
    void aFinishedGameGetsItsFinalStatsOnceThenStops() throws InterruptedException {
        FakeApi api = new FakeApi();
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(110)), "FINISHED", false);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.answers.put("/fixtures/date/" + f.get("matchDate"), data(f));
        api.answers.put("/fixtures/g1/statistics", statsBody());
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("g1"));
        waitFor(() -> api.callsTo("/statistics") == 1);
        assertThat(api.callsTo("/statistics")).isEqualTo(1);
        assertThat(String.valueOf(((Map<?, ?>) live.match("g1").get("stats")).get("final"))).isEqualTo("true");
        assertThat(live.state().get("watching")).isEqualTo(false);   // nothing left to refresh
    }

    @Test
    void postponedGamesAreNotRefreshed() {
        FakeApi api = new FakeApi();
        api.answers.put("/leagues/" + LEAGUE + "/fixtures",
                data(fixture("g1", Instant.now().minus(Duration.ofMinutes(10)), "POSTPONED", false)));
        LiveService live = service(api, 20);
        live.loadFixtures(true);
        live.track(List.of("g1"));
        live.tick();
        assertThat(api.calls).hasSize(1);
    }

    @Test
    void aStatsErrorIsShownAsAWarningAndTheRestCarriesOn() throws InterruptedException {
        FakeApi api = new FakeApi();
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(20)), "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.answers.put("/fixtures/date/" + f.get("matchDate"), data(f));
        api.failing.add("/fixtures/g1/statistics");
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("g1"));
        waitFor(() -> live.state().get("lastRefresh") != null);
        assertThat(String.valueOf(live.state().get("warning"))).startsWith("Last refresh had errors");
        assertThat(live.match("g1").get("stats")).isNull();
    }

    @Test
    void gamesFromBeforeYesterdayAreForgotten() {
        FakeApi api = new FakeApi();
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(
                fixture("old", Instant.now().minus(Duration.ofDays(2)), "FINISHED", false),
                fixture("new", Instant.now().plus(Duration.ofHours(2)), "SCHEDULED", false)));
        LiveService live = service(api, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) live.loadFixtures(true).get("fixtures");
        assertThat(fixtures).extracting(x -> x.get("id")).containsExactly("new");
    }

    @Test
    void unknownGameHasNoData() {
        Map<String, Object> m = service(new FakeApi(), 20).match("nope");
        assertThat(m.get("fixture")).isNull();
        assertThat(m.get("tracked")).isEqualTo(false);
        assertThat(m.get("stats")).isNull();
    }

    @Test
    void twoTickedGamesInOneCompetitionShareOneScoresCall() throws InterruptedException {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().minus(Duration.ofMinutes(30));
        Map<String, Object> a = fixture("a", ko, "LIVE", true), b = fixture("b", ko, "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(a, b));
        api.answers.put("/fixtures/date/" + a.get("matchDate"), data(a, b));
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("a", "b"));
        waitFor(() -> api.callsTo("/statistics") == 2);
        assertThat(api.callsTo("/fixtures/date/")).isEqualTo(1);
        assertThat(api.callsTo("/statistics")).isEqualTo(2);
    }

    @Test
    void aFailingScoresCallIsAWarningAndStatsAreStillFetched() throws InterruptedException {
        FakeApi api = new FakeApi();
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(20)), "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.failing.add("/fixtures/date/" + f.get("matchDate"));
        api.answers.put("/fixtures/g1/statistics", statsBody());
        LiveService live = service(api, 20);
        live.loadFixtures(true);

        live.track(List.of("g1"));
        waitFor(() -> live.state().get("lastRefresh") != null);
        assertThat(String.valueOf(live.state().get("warning"))).contains("Last refresh had errors");
        assertThat(live.match("g1").get("stats")).isNotNull();
    }

    @Test
    void gamesThatEndedHoursAgoAreNotRefreshed() {
        FakeApi api = new FakeApi();
        api.answers.put("/leagues/" + LEAGUE + "/fixtures",
                data(fixture("g1", Instant.now().minus(Duration.ofHours(4)), "LIVE", true)));
        LiveService live = service(api, 20);
        live.loadFixtures(true);
        live.track(List.of("g1"));
        live.tick();
        assertThat(api.calls).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statsAndCallsLeftSurviveARestart() throws InterruptedException {
        FakeApi api = new FakeApi();
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(20)), "LIVE", true);
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.answers.put("/fixtures/date/" + f.get("matchDate"), data(f));
        api.answers.put("/fixtures/g1/statistics", statsBody());
        api.restoreUsage(Map.of("remaining", "900", "limit", "1000"));
        LiveService live = service(api, 20);
        live.loadFixtures(true);
        live.track(List.of("g1"));
        waitFor(() -> api.callsTo("/statistics") == 1);
        waitFor(() -> live.state().get("lastRefresh") != null);

        FakeApi fresh = new FakeApi();
        LiveService restarted = service(fresh, 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) restarted.match("g1").get("stats");
        assertThat(stats.get("xg")).isEqualTo(List.of(1.34, 0.79));
        assertThat((List<Map<String, Object>>) stats.get("rows")).extracting(r -> r.get("label"))
                .startsWith("xG (est.)", "Shots", "Shots on target");
        assertThat((List<Map<String, Object>>) stats.get("homePlayers")).extracting(p -> p.get("name")).containsExactly("Kane", "Son");
        assertThat(restarted.state().get("tracked")).isEqualTo(List.of("g1"));
        assertThat(fresh.remaining()).isEqualTo(900);
        assertThat(restarted.state().get("lastRefresh")).isNotNull();
        restarted.tick();                                  // refreshed less than 5 minutes ago: waits
        assertThat(fresh.calls).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheTopFiveShootersAreKeptAndOddValuesCountAsZero() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            players.add(player("P" + i, String.valueOf(i), String.valueOf(i % 3), "0"));
        }
        players.add(player("Odd", "two", "x", "0"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("match", Map.of("fullTime", List.of(row("Shots Outside Box", "4", "x"))));
        d.put("players", Map.of("home", players, "away", "not a list"));
        Map<String, Object> s = LiveService.computeStats(Map.of("data", d));

        List<Map<String, Object>> home = (List<Map<String, Object>>) s.get("homePlayers");
        assertThat(home).hasSize(5);
        assertThat(home.get(0).get("onTarget")).isEqualTo(2);
        assertThat((List<Object>) s.get("awayPlayers")).isEmpty();
        assertThat((List<Double>) s.get("xg")).containsExactly(0.14, 0.0);   // no inside-box count: outside only
    }

    @Test
    void statsWithNothingInThemGiveAnEmptyTable() {
        Map<String, Object> s = LiveService.computeStats(Map.of("data", "nothing"));
        assertThat((List<?>) s.get("rows")).isEmpty();
        assertThat(s.get("xg")).isNull();
    }

    @Test
    void statusWordsForFinishedAndCalledOff() {
        LiveService live = service(new FakeApi(), 20);
        for (String st : new String[] {"FT", "AET", "AFTER_PENALTIES", "FINISHED"}) {
            Map<String, Object> f = LiveService.summary(fixture("x", Instant.now(), st, false), "epl");
            assertThat(f.get("status")).isEqualTo(st);
        }
        assertThat(live.state().get("watching")).isEqualTo(false);
    }

}
