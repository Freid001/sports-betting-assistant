package com.sportsbettingassistant.football.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sportsbettingassistant.support.FakeServer;

class GoalApiClientTest {

    private static final Map<String, String> RATE = Map.of(
            "X-RateLimit-Limit", "1000", "X-RateLimit-Remaining", "973", "X-RateLimit-Reset", "1795132800", "X-RateLimit-Type", "DAILY");

    private final FakeServer api = new FakeServer();

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    void sendsTheKeyAsABearerTokenAndRemembersCallsLeft() {
        api.answer("/fixtures/live", 200, "{\"data\":[{\"id\":\"x\"}]}", RATE);
        GoalApiClient goal = new GoalApiClient(" k ", api.url());
        assertThat(goal.remaining()).isEqualTo(-1);

        Map<String, Object> body = goal.getJson("/fixtures/live", Map.of("leagueId", "L1"));

        assertThat(body.get("data")).isInstanceOf(java.util.List.class);
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer k");
        assertThat(api.last().query()).isEqualTo("leagueId=L1");
        assertThat(goal.remaining()).isEqualTo(973);
        assertThat(goal.usage()).containsEntry("limit", "1000").containsEntry("reset", "1795132800").containsKey("checkedAt");
    }

    @Test
    void errorsThrowWithTheApisMessage() {
        api.answer("/fixtures/live", 401, "{\"message\":\"invalid key\"}");
        assertThatThrownBy(() -> new GoalApiClient("k", api.url()).getJson("/fixtures/live", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOAL API 401").hasMessageContaining("invalid key");
    }

    @Test
    void unsafePathsAndAMissingKeyAreRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> new GoalApiClient("k", api.url()).getJson("/fixtures/../admin", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        GoalApiClient noKey = new GoalApiClient("", api.url());
        assertThat(noKey.configured()).isFalse();
        assertThatThrownBy(() -> noKey.getJson("/fixtures/live", Map.of())).hasMessageContaining("GOAL_API_KEY");
        assertThat(api.requests).isEmpty();
    }

    @Test
    void savedUsageIsRestoredAfterARestartButNotOverFreshNumbers() {
        GoalApiClient goal = new GoalApiClient("k", api.url());
        goal.restoreUsage(Map.of("remaining", "500"));
        assertThat(goal.remaining()).isEqualTo(500);
        goal.restoreUsage(Map.of("remaining", "1"));   // already known: ignored
        assertThat(goal.remaining()).isEqualTo(500);
        goal.restoreUsage(null);
        goal.restoreUsage(Map.of("remaining", "lots"));
        assertThat(goal.remaining()).isEqualTo(500);
    }

    @Test
    void testRouteReturnsTheJsonAndCallsLeftHeaders() {
        api.answer("/leagues", 200, "{\"data\":[]}", RATE);
        Map<String, String> params = new HashMap<>(Map.of("path", "/leagues", "page", "2"));
        var res = new GoalApiClient("k", api.url()).test("/leagues", params);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("{\"data\":[]}");
        assertThat(res.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("973");
        assertThat(api.last().query()).isEqualTo("page=2");
    }

    @Test
    void testRouteRefusesBadPathsAndExplainsAMissingKey() {
        var bad = new GoalApiClient("k", api.url()).test("x/../y", new HashMap<>());
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        var noKey = new GoalApiClient("", api.url()).test("/leagues", new HashMap<>());
        assertThat(noKey.getStatusCode().value()).isEqualTo(503);
        assertThat(noKey.getBody()).contains("GOAL_API_KEY");
    }

    @Test
    void longErrorMessagesAreCutShortAndOddCallsLeftAreUnknown() {
        api.answer("/fixtures/live", 500, "x".repeat(500), Map.of("X-RateLimit-Remaining", "lots"));
        GoalApiClient goal = new GoalApiClient(null, api.url());
        assertThat(goal.configured()).isFalse();
        GoalApiClient keyed = new GoalApiClient("k", api.url());
        assertThatThrownBy(() -> keyed.getJson("/fixtures/live", Map.of()))
                .hasMessage("GOAL API 500: " + "x".repeat(200));
        assertThat(keyed.remaining()).isEqualTo(-1);
    }
}
