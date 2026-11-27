package com.sportsbettingassistant.football.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import com.sportsbettingassistant.json.Json;
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
        f.put("matchMinute", live ? "30" : "Not Started");
        f.put("matchElapsed", live ? "30" : "null");
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
        double[] xg = GoalData.xg(Map.of(
                "Shots Inside Box", new String[] {"11", "6"},
                "Shots Outside Box", new String[] {"3", "2"}));
        assertThat(xg[0]).isCloseTo(11 * 0.12 + 3 * 0.035, within(1e-9));
        assertThat(xg[1]).isCloseTo(6 * 0.12 + 2 * 0.035, within(1e-9));
    }

    @Test
    void penaltiesCountAtPenaltyValueNotAsInsideBoxShots() {
        double[] xg = GoalData.xg(Map.of(
                "Shots Inside Box", new String[] {"14", "10"},
                "Shots Outside Box", new String[] {"12", "2"},
                "Penalty", new String[] {"1", "1"}));
        assertThat(xg[0]).isCloseTo(13 * 0.12 + 12 * 0.035 + 0.76, within(1e-9));
        assertThat(xg[1]).isCloseTo(9 * 0.12 + 2 * 0.035 + 0.76, within(1e-9));
    }

    @Test
    void stoppageTimeIsCountedFromWhenAHalfFirstSatAt90() {
        Instant t = Instant.parse("2026-11-26T21:48:00Z");
        Map<String, Object> s = new java.util.HashMap<>(Map.of("id", "st1", "elapsed", "90", "period", "SECOND_HALF", "live", true));
        GoalData.stoppage(s, t);
        assertThat(s.get("extra")).isNull();                  // just got to 90
        s.remove("extra");
        GoalData.stoppage(s, t.plusSeconds(4 * 60 + 20));
        assertThat(s.get("extra")).isEqualTo("4");            // 90+4'
        Map<String, Object> sent = new java.util.HashMap<>(Map.of("id", "st1", "elapsed", "90", "period", "SECOND_HALF",
                "live", true, "extra", "6"));
        GoalData.stoppage(sent, t.plusSeconds(300));
        assertThat(sent.get("extra")).isEqualTo("6");         // GOAL API's own added minutes win
        Map<String, Object> first = new java.util.HashMap<>(Map.of("id", "st1", "elapsed", "44", "period", "FIRST_HALF", "live", true));
        GoalData.stoppage(first, t);
        assertThat(first.get("extra")).isNull();
    }

    private static Map<String, Object> game(String status, String period, String elapsed, String home, String away, boolean live) {
        Map<String, Object> f = new java.util.HashMap<>();
        f.put("status", status); f.put("period", period); f.put("elapsed", elapsed); f.put("minute", elapsed);
        f.put("homeScore", home); f.put("awayScore", away); f.put("live", live);
        return f;
    }

    private static Map<String, List<Map<String, Object>>> shooters(String side, Object... nameShotsOnTargetGoals) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < nameShotsOnTargetGoals.length; i += 4) {
            list.add(Map.of("name", nameShotsOnTargetGoals[i], "shots", nameShotsOnTargetGoals[i + 1],
                    "onTarget", nameShotsOnTargetGoals[i + 2], "goals", nameShotsOnTargetGoals[i + 3]));
        }
        Map<String, List<Map<String, Object>>> m = new java.util.HashMap<>();
        m.put(side, list);
        m.put("home".equals(side) ? "away" : "home", List.of());
        return m;
    }

    private static Map<String, Object> cards(int homeYellow, int awayYellow, int homeRed, int awayRed) {
        return Map.of("rows", List.of(
                Map.of("label", "Yellow cards", "home", String.valueOf(homeYellow), "away", String.valueOf(awayYellow)),
                Map.of("label", "Red cards", "home", String.valueOf(homeRed), "away", String.valueOf(awayRed))));
    }

    @Test
    void theTimelineFindsKickOffGoalsWithScorersCardsShotsAndFullTime() {
        List<Map<String, Object>> sofar = new java.util.ArrayList<>();
        var ko = MatchTimeline.events(game("SCHEDULED", "NOT_STARTED", null, "0", "0", false),
                game("LIVE", "FIRST_HALF", "1", "0", "0", true), null, cards(0, 0, 0, 0), null, shooters("home"), sofar);
        assertThat(ko).extracting(e -> e.get("kind")).containsExactly("kickoff");

        var goal = MatchTimeline.events(game("LIVE", "FIRST_HALF", "22", "0", "0", true),
                game("LIVE", "FIRST_HALF", "23", "1", "0", true), cards(0, 0, 0, 0), cards(0, 1, 0, 0),
                shooters("home", "Kane", 2, 1, 0, "Son", 1, 0, 0), shooters("home", "Kane", 3, 2, 1, "Son", 2, 1, 0), sofar);
        assertThat(goal).extracting(e -> e.get("kind")).containsExactly("goal", "yellow", "shot");
        assertThat(goal.get(0)).containsEntry("player", "Kane").containsEntry("side", "home")
                .containsEntry("score", "1–0").containsEntry("minute", "23'");
        assertThat(goal.get(1)).containsEntry("side", "away");
        assertThat(goal.get(2)).containsEntry("player", "Son");   // Kane's shot on target was his goal: not twice

        sofar.addAll(goal);
        var noScorer = MatchTimeline.events(game("LIVE", "SECOND_HALF", "60", "1", "0", true),
                game("LIVE", "SECOND_HALF", "61", "2", "0", true), cards(0, 1, 0, 0), cards(0, 1, 0, 0),
                shooters("home", "Kane", 3, 2, 1), shooters("home", "Kane", 3, 2, 1), sofar);
        assertThat(noScorer.get(0)).containsEntry("kind", "goal").containsEntry("player", null);
        sofar.addAll(noScorer);
        var late = MatchTimeline.events(game("LIVE", "SECOND_HALF", "61", "2", "0", true),
                game("LIVE", "SECOND_HALF", "62", "2", "0", true), cards(0, 1, 0, 0), cards(0, 1, 0, 0),
                shooters("home", "Kane", 3, 2, 1), shooters("home", "Kane", 4, 3, 2), sofar);
        assertThat(late).isEmpty();   // the goal at 61' gets Kane filled in, not a new event
        assertThat(sofar.get(sofar.size() - 1)).containsEntry("player", "Kane");

        var ft = MatchTimeline.events(game("LIVE", "SECOND_HALF", "90", "2", "0", true),
                game("FINISHED", "FINISHED", null, "2", "0", true), cards(0, 1, 0, 0), cards(0, 1, 0, 0), null, null, sofar);
        assertThat(ft).extracting(e -> e.get("kind")).containsExactly("ft");
        assertThat(ft.get(0)).containsEntry("minute", "FT").containsEntry("score", "2–0");
    }

    @Test
    void substitutionsAndCommentaryMomentsWithAPlayerGoInTheTimeline() {
        var subs = MatchTimeline.officialSubs(Json.parse("{\"data\":[{\"time\":\"46\",\"substitution\":\"D. Welbeck | E. Emegha\","
                + "\"team\":\"away\"},{\"time\":\"72\",\"substitution\":\"Igor Jesus | D. Ndoye\",\"team\":\"home\"}]}"));
        assertThat(subs).extracting(e -> e.get("kind")).containsExactly("sub", "sub");
        assertThat(subs.get(0)).containsEntry("side", "away").containsEntry("player", "E. Emegha")
                .containsEntry("info", "for D. Welbeck").containsEntry("minute", "46'").containsEntry("half", 2);

        var said = MatchTimeline.commentary(Json.parse("{\"data\":["
                + "{\"time\":\"53:09\",\"text\":\"Nottingham shot on goal\",\"state\":\"J McAtee,\"},"
                + "{\"time\":\"12:40\",\"text\":\"Chelsea free kick\",\"state\":\"C Palmer\"},"
                + "{\"time\":\"05:22\",\"text\":\"Nottingham goal\",\"state\":\"\"},"
                + "{\"time\":\"20:01\",\"text\":\"Chelsea in possession\",\"state\":\"M Caicedo\"},"
                + "{\"time\":\"30:00\",\"text\":\"Chelsea shot on goal\",\"state\":\"\"}]}"), "Nottingham", "Chelsea");
        assertThat(said).extracting(e -> e.get("kind")).containsExactly("shot", "freekick", "possession");   // only with a player
        assertThat(said.get(0)).containsEntry("side", "home").containsEntry("player", "J McAtee")
                .containsEntry("minute", "54'").containsEntry("half", 2);
        assertThat(said.get(1)).containsEntry("side", "away").containsEntry("half", 1);
    }

    @Test
    void theKeepersComeFromTheLineUps() {
        var gk = MatchTimeline.keepersOf(Json.parse("{\"data\":{\"home\":{\"startingLineups\":["
                + "{\"lineupPlayer\":\"Jair\",\"playerPosition\":\"Defenders\"},"
                + "{\"lineupPlayer\":\"Matz Sels\",\"playerPosition\":\"Goalkeepers\"}]},"
                + "\"away\":{\"startingLineups\":[{\"lineupPlayer\":\"R. Sanchez\",\"playerPosition\":\"Goalkeepers\"}]}}}"));
        assertThat(gk).containsExactly("Matz Sels", "R. Sanchez");
        assertThat(MatchTimeline.keepersOf(Json.parse("{\"data\":{}}"))).containsExactly(null, null);
    }

    @Test
    void goalApisOwnGoalsAndCardsGiveRealMinutesScorersAssistsAndPenalties() {
        Map<String, Object> goals = Json.parse("{\"data\":["
                + "{\"time\":\"5\",\"timeNum\":5,\"type\":\"GOAL\",\"homeScorer\":null,\"awayScorer\":\"C. Humphreys\","
                + "\"awayAssist\":\"A. Fletcher\",\"score\":\"0 - 1\",\"info\":null,\"scoreInfoTime\":\"1st Half\"},"
                + "{\"time\":\"34\",\"timeNum\":34,\"type\":\"GOAL\",\"homeScorer\":\"I. Hutchinson\",\"awayScorer\":null,"
                + "\"score\":\"1 - 2\",\"info\":\"Penalty\",\"scoreInfoTime\":\"1st Half\"},"
                + "{\"time\":\"90+3\",\"timeNum\":90,\"type\":\"GOAL\",\"homeScorer\":\"O. Bailey\",\"score\":\"2 - 3\","
                + "\"scoreInfoTime\":\"2nd Half\"}]}");
        var g = MatchTimeline.officialGoals(goals);
        assertThat(g).hasSize(3);
        assertThat(g.get(0)).containsEntry("side", "away").containsEntry("player", "C. Humphreys")
                .containsEntry("assist", "A. Fletcher").containsEntry("score", "0–1").containsEntry("minute", "5'")
                .containsEntry("half", 1).containsEntry("source", "api");
        assertThat(g.get(1)).containsEntry("side", "home").containsEntry("info", "Penalty");
        assertThat(g.get(2)).containsEntry("minute", "90+3'").containsEntry("half", 2);
        assertThat((int) g.get(2).get("order")).isGreaterThan((int) g.get(1).get("order"));

        Map<String, Object> cards = Json.parse("{\"data\":["
                + "{\"time\":\"11\",\"timeNum\":11,\"card\":\"yellow card\",\"homeFault\":null,\"awayFault\":\"B. Mumba\",\"scoreInfoTime\":\"1st Half\"},"
                + "{\"time\":\"33\",\"timeNum\":33,\"card\":\"red card\",\"homeFault\":null,\"awayFault\":\"B. Mumba\",\"scoreInfoTime\":\"1st Half\"}]}");
        var c = MatchTimeline.officialCards(cards);
        assertThat(c).extracting(e -> e.get("kind")).containsExactly("yellow", "red");
        assertThat(c.get(1)).containsEntry("player", "B. Mumba").containsEntry("side", "away").containsEntry("minute", "33'");
    }

    @Test
    void noXgWithoutShotSplit() {
        assertThat(GoalData.xg(Map.of("Shots Total", new String[] {"5", "3"}))).isNull();
    }

    // ---------------------------------------------------------------- stats layout

    @Test
    @SuppressWarnings("unchecked")
    void statsPutXgFirstAndKeepTheFirstCornersRow() {
        Map<String, Object> s = GoalData.computeStats(statsBody());
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
        List<Map<String, Object>> players = (List<Map<String, Object>>) GoalData.computeStats(statsBody()).get("homePlayers");
        assertThat(players).extracting(p -> p.get("name")).containsExactly("Kane", "Son");
        assertThat(players.get(0)).containsEntry("onTarget", 3).containsEntry("goals", 1);
    }

    @Test
    void summaryReadsLiveFlagAndTurnsNullTextIntoNull() {
        Map<String, Object> live = GoalData.summary(fixture("a", Instant.now(), "LIVE", true), "epl");
        assertThat(live).containsEntry("live", true).containsEntry("homeScore", "1").containsEntry("tournament", "epl");
        Map<String, Object> later = GoalData.summary(fixture("b", Instant.now(), "SCHEDULED", false), "epl");
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
        Map<String, Object> s = GoalData.computeStats(Map.of("data", d));

        List<Map<String, Object>> home = (List<Map<String, Object>>) s.get("homePlayers");
        assertThat(home).hasSize(5);
        assertThat(home.get(0).get("onTarget")).isEqualTo(2);
        assertThat((List<Object>) s.get("awayPlayers")).isEmpty();
        assertThat((List<Double>) s.get("xg")).containsExactly(0.14, 0.0);   // no inside-box count: outside only
    }

    @Test
    void statsWithNothingInThemGiveAnEmptyTable() {
        Map<String, Object> s = GoalData.computeStats(Map.of("data", "nothing"));
        assertThat((List<?>) s.get("rows")).isEmpty();
        assertThat(s.get("xg")).isNull();
    }

    @Test
    void statusWordsForFinishedAndCalledOff() {
        LiveService live = service(new FakeApi(), 20);
        for (String st : new String[] {"FT", "AET", "AFTER_PENALTIES", "FINISHED"}) {
            Map<String, Object> f = GoalData.summary(fixture("x", Instant.now(), st, false), "epl");
            assertThat(f.get("status")).isEqualTo(st);
        }
        assertThat(live.state().get("watching")).isEqualTo(false);
    }


    // ---------------------------------------------------------------- replay a game once (snapshots)

    /** Spurs v Ipswich as GOAL API sent it after full time: 1-0 at half time, 2-0 at the end. */
    private static Map<String, Object> finishedSpurs(String id, Instant kickoff) {
        Map<String, Object> f = new LinkedHashMap<>(fixture(id, kickoff, "FINISHED", false));
        f.put("homeTeamScore", "2");
        f.put("awayTeamScore", "0");
        f.put("homeTeamHalftimeScore", "1");
        f.put("awayTeamHalftimeScore", "0");
        f.put("matchMinute", "Finished");
        f.put("matchPeriod", "FINISHED");
        return f;
    }

    private static Map<String, Object> statsWithHalves() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("match", Map.of(
                "fullTime", List.of(row("Shots On Goal", "9", "3"), row("Shots Inside Box", "16", "5"),
                        row("Shots Outside Box", "2", "6"), row("Dangerous Attacks", "59", "41"), row("Corners", "8", "6")),
                "firstHalf", List.of(row("Shots On Goal", "7", "2"), row("Shots Inside Box", "11", "2"),
                        row("Shots Outside Box", "2", "2"), row("Dangerous Attacks", "41", "16"), row("Corners", "5", "2")),
                "secondHalf", List.of()));
        d.put("players", Map.of("home", List.of(player("Solanke", "4", "2", "1")), "away", List.of()));
        return Map.of("data", d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayFindsAPastGameByLooseNamesAndSavesHalfTimeAndFullTimeSnapshots() {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().minus(Duration.ofDays(4));
        String date = LocalDate.ofInstant(ko, ZoneOffset.UTC).toString();
        Map<String, Object> other = new LinkedHashMap<>(finishedSpurs("other", ko));
        other.put("homeTeamName", "Chelsea");
        other.put("awayTeamName", "Leeds");
        api.answers.put("/fixtures/date/" + date, data(other, finishedSpurs("g1", ko)));
        api.answers.put("/fixtures/g1/statistics", statsWithHalves());
        LiveService live = service(api, 20);

        Map<String, Object> m = live.replay("epl", date, ko.toString(), "Tottenham Hotspur", "Ipswich Town");

        assertThat(api.calls).containsExactly("/fixtures/date/" + date, "/fixtures/g1/statistics");
        assertThat(((Map<String, Object>) m.get("fixture")).get("id")).isEqualTo("g1");
        assertThat(m.get("tracked")).isEqualTo(true);
        List<Map<String, Object>> snaps = (List<Map<String, Object>>) m.get("snapshots");
        assertThat(snaps).extracting(x -> x.get("period")).containsExactly("HT", "FT");
        assertThat(snaps.get(0).get("homeScore")).isEqualTo("1");
        assertThat(snaps.get(1).get("homeScore")).isEqualTo("2");
        List<Double> htXg = (List<Double>) snaps.get(0).get("xg");
        assertThat(htXg.get(0)).isCloseTo(11 * 0.12 + 2 * 0.035, within(0.01));
        assertThat(((Map<String, Object>) m.get("stats")).get("final")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayedGamesAreKeptAfterARestartAndNotForgottenAfterADay() {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().minus(Duration.ofDays(4));
        String date = LocalDate.ofInstant(ko, ZoneOffset.UTC).toString();
        api.answers.put("/fixtures/date/" + date, data(finishedSpurs("g1", ko)));
        api.answers.put("/fixtures/g1/statistics", statsWithHalves());
        service(api, 20).replay("epl", date, null, "Tottenham", "Ipswich");

        LiveService restarted = service(new FakeApi(), 20);
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) restarted.state().get("fixtures");
        assertThat(fixtures).extracting(x -> x.get("id")).containsExactly("g1");
        assertThat(fixtures.get(0).get("saved")).isEqualTo(true);
        List<Map<String, Object>> snaps = (List<Map<String, Object>>) restarted.match("g1").get("snapshots");
        assertThat(snaps).extracting(x -> x.get("period")).containsExactly("HT", "FT");
        assertThat((List<Map<String, Object>>) snaps.get(1).get("rows")).isNotEmpty();
    }

    @Test
    void replayOfAnUnknownGameSaysSo() {
        FakeApi api = new FakeApi();
        LiveService live = service(api, 20);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.replay("epl", "2026-11-21", null, "Arsenal", "Chelsea"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Couldn't find Arsenal v Chelsea");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.replay("nope", "2026-11-21", null, "A", "B"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No GOAL API league");
    }

    @Test
    void teamNamesMatchLoosely() {
        assertThat(TeamNames.similar("Tottenham Hotspur", "Tottenham")).isEqualTo(2);
        assertThat(TeamNames.similar("AFC Bournemouth", "Bournemouth")).isEqualTo(3);
        assertThat(TeamNames.similar("Manchester United", "Manchester City")).isEqualTo(1);
        assertThat(TeamNames.similar("Ipswich", "Leeds")).isEqualTo(0);
    }

    @Test
    void goalApiAndOddsApiNamesForTheSameTeamsMatch() {
        // GOAL API name, Odds API name: both teams together must score more than 3
        assertThat(TeamNames.similar("Sporting CP", "Sporting Lisbon") + TeamNames.similar("Manchester Utd", "Manchester United")).isGreaterThan(3);
        assertThat(TeamNames.similar("PSG", "Paris Saint Germain") + TeamNames.similar("AS Roma", "AS Roma")).isGreaterThan(3);
        assertThat(TeamNames.similar("Atl. Madrid", "Atl\u00e9tico Madrid")).isEqualTo(3);
        assertThat(TeamNames.similar("Atl. Madrid", "Atlu00e9tico Madrid")).isEqualTo(3);     // a name saved before the fix
        assertThat(TeamNames.similar("Bodo/Glimt", "Bod\u00f8/Glimt")).isEqualTo(3);
        assertThat(TeamNames.similar("Bodo/Glimt", "Bodu00f8/Glimt")).isEqualTo(3);
        assertThat(TeamNames.similar("Viking", "Viking FK")).isEqualTo(3);
        assertThat(TeamNames.similar("Club Brugge KV", "Club Brugge")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- following from the match page

    @Test
    @SuppressWarnings("unchecked")
    void aGameFollowedDaysAheadIsTickedOnMatchDayWithOneCall() {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().plus(Duration.ofDays(3));
        String ukDate = LocalDate.ofInstant(ko, java.time.ZoneId.of("Europe/London")).toString();
        api.answers.put("/fixtures/date/" + ukDate, data(fixture("g1", ko, "NOT_STARTED", false)));
        LiveService live = service(api, 20);

        Map<String, Object> st = live.follow("epl", ko.toString(), "Tottenham Hotspur", "Ipswich Town");
        assertThat(api.calls).isEmpty();                                  // not match day yet: no calls
        assertThat((List<Object>) st.get("following")).hasSize(1);
        assertThat((List<Object>) st.get("tracked")).isEmpty();
        live.follow("epl", ko.toString(), "Tottenham Hotspur", "Ipswich Town");   // twice: still one
        assertThat((List<Object>) live.state().get("following")).hasSize(1);

        LiveService restarted = service(api, 20);                         // kept after a restart
        restarted.resolveFollowing(ko.minus(Duration.ofHours(1)));        // match day
        assertThat(api.calls).containsExactly("/fixtures/date/" + ukDate);
        assertThat((List<Object>) restarted.state().get("tracked")).containsExactly("g1");
        assertThat((List<Object>) restarted.state().get("following")).isEmpty();
        restarted.resolveFollowing(ko.minus(Duration.ofMinutes(30)));
        assertThat(api.calls).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unfollowForgetsAWaitingGameAndUnticksAListedOne() {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().plus(Duration.ofDays(3));
        LiveService live = service(api, 20);
        live.follow("epl", ko.toString(), "Tottenham", "Ipswich");
        live.unfollow("epl", ko.toString(), "Tottenham", "Ipswich");
        assertThat((List<Object>) live.state().get("following")).isEmpty();

        Instant soon = Instant.now().plus(Duration.ofHours(1));
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(fixture("g2", soon, "NOT_STARTED", false)));
        live.loadFixtures(true);
        live.follow("epl", soon.toString(), "Tottenham", "Ipswich");      // already listed: ticked straight away
        assertThat((List<Object>) live.state().get("tracked")).containsExactly("g2");
        live.unfollow("epl", soon.toString(), "Tottenham", "Ipswich");
        assertThat((List<Object>) live.state().get("tracked")).isEmpty();
    }

    @Test
    void timelinePointsFromTheMinute() {
        Map<String, Object> f = new LinkedHashMap<>(Map.of("live", true, "status", "LIVE"));
        f.put("elapsed", "5");
        assertThat(MatchTimeline.pointFor(f)).isNull();
        f.put("elapsed", "12");
        assertThat(MatchTimeline.pointFor(f)).isNull();                     // no 10' window
        f.put("elapsed", "27");
        assertThat(MatchTimeline.pointFor(f)).isNull();                     // the first window is 30'
        f.put("elapsed", "30");
        assertThat(MatchTimeline.pointFor(f)).isEqualTo("30");
        f.put("elapsed", "50");
        assertThat(MatchTimeline.pointFor(f)).isNull();                     // HT comes from the first-half stats
        f.put("elapsed", "65");
        assertThat(MatchTimeline.pointFor(f)).isEqualTo("60");
        f.put("elapsed", "88");
        assertThat(MatchTimeline.pointFor(f)).isEqualTo("80");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLiveGameSavesTheTimelinePointItHasReachedOnce() throws InterruptedException {
        FakeApi api = new FakeApi();
        Map<String, Object> f = fixture("g1", Instant.now().minus(Duration.ofMinutes(30)), "LIVE", true);   // 30'
        api.answers.put("/leagues/" + LEAGUE + "/fixtures", data(f));
        api.answers.put("/fixtures/date/" + f.get("matchDate"), data(f));
        api.answers.put("/fixtures/g1/statistics", statsBody());
        LiveService live = service(api, 20);
        live.loadFixtures(true);
        live.track(List.of("g1"));
        waitFor(() -> api.callsTo("/statistics") == 1);
        waitFor(() -> !((List<?>) live.match("g1").get("snapshots")).isEmpty());

        List<Map<String, Object>> snaps = (List<Map<String, Object>>) live.match("g1").get("snapshots");
        assertThat(snaps).extracting(x -> x.get("period")).containsExactly("30");
        assertThat(snaps.get(0).get("homeScore")).isEqualTo("1");
        assertThat((List<Object>) snaps.get(0).get("rows")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void postMatchLoadsEveryFinishedGameWithOneListCallPerDayAndSkipsOnesAlreadyLoaded() {
        FakeApi api = new FakeApi();
        Instant ko = Instant.now().minus(Duration.ofDays(2));
        String date = LocalDate.ofInstant(ko, java.time.ZoneId.of("Europe/London")).toString();
        Map<String, Object> other = new LinkedHashMap<>(finishedSpurs("g2", ko));
        other.put("homeTeamName", "Chelsea");
        other.put("awayTeamName", "Leeds");
        api.answers.put("/fixtures/date/" + date, data(finishedSpurs("g1", ko), other));
        api.answers.put("/fixtures/g1/statistics", statsWithHalves());
        api.answers.put("/fixtures/g2/statistics", statsWithHalves());
        LiveService live = service(api, 20);
        List<Map<String, Object>> games = List.of(
                Map.of("tournament", "epl", "kickoff", ko.toString(), "home", "Tottenham Hotspur", "away", "Ipswich Town"),
                Map.of("tournament", "epl", "kickoff", ko.toString(), "home", "Chelsea", "away", "Leeds United"),
                Map.of("tournament", "epl", "kickoff", ko.toString(), "home", "Arsenal", "away", "Everton"),
                Map.of("tournament", "nowhere", "kickoff", ko.toString(), "home", "A", "away", "B"));

        Map<String, Object> out = live.postMatch(games);

        assertThat(api.callsTo("/fixtures/date/")).isEqualTo(1);       // one list call for the day
        assertThat(api.callsTo("/statistics")).isEqualTo(2);           // one stats call per game found
        assertThat(out).containsEntry("loaded", 2).containsEntry("calls", 3);
        assertThat((List<String>) out.get("missing")).containsExactly("Arsenal v Everton", "A v B");
        assertThat((List<Map<String, Object>>) live.match("g1").get("snapshots")).extracting(x -> x.get("period"))
                .containsExactly("HT", "FT");
        assertThat((List<Object>) live.state().get("tracked")).isEmpty();   // loaded, not followed

        Map<String, Object> again = live.postMatch(games.subList(0, 1));
        assertThat(again).containsEntry("loaded", 0).containsEntry("skipped", 1);
        assertThat(api.callsTo("/statistics")).isEqualTo(2);           // no second stats call
    }

    @org.junit.jupiter.api.Test
    void aGameBeingPlayedIsLiveEvenIfMatchLiveStillSaysNo() {
        java.util.Map<String, Object> kickedOff = new java.util.HashMap<>(java.util.Map.of(
                "matchStatus", "LIVE", "matchLive", "0", "matchPeriod", "FIRST_HALF", "matchMinute", "1"));
        assertThat(GoalData.summary(kickedOff, "england_league1").get("live")).isEqualTo(true);
        assertThat(GoalData.playing(java.util.Map.of("matchStatus", "FINISHED", "matchPeriod", "FINISHED"))).isFalse();
        assertThat(GoalData.playing(java.util.Map.of("matchStatus", "SCHEDULED", "matchPeriod", "null"))).isFalse();
        assertThat(GoalData.playing(java.util.Map.of("matchStatus", "HALF_TIME", "matchPeriod", "HALF_TIME"))).isTrue();
    }
}
