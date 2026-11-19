package com.sportsbettingassistant.football;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.sportsbettingassistant.support.FakeServer;

/** football-data.org: the key is sent, calls wait for a free slot, a 429 is retried once, errors come back as they are. */
class FootballDataClientTest {

    private static final Map<String, String> FINISHED = FootballDataClient.params("status", "FINISHED");

    FakeServer api;

    @BeforeEach
    void start() {
        api = new FakeServer();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private FootballDataClient client(String key) {
        return new FootballDataClient(key, api.url(), 10);
    }

    @Test
    void sendsTheKeyAndParamsAndReturnsTheAnswer() {
        api.answer("/competitions/PL/matches", 200, "{\"matches\":[]}");
        ResponseEntity<String> res = client("k").fetch("/competitions/PL/matches", FINISHED);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("{\"matches\":[]}");
        assertThat(api.last().header("X-Auth-Token")).isEqualTo("k");
        assertThat(api.last().query()).isEqualTo("status=FINISHED");
    }

    @Test
    void errorsComeBackWithTheirStatusAndAnEmptyBodyBecomesEmptyJson() {
        api.answer("/competitions/CL/scorers", 403, "{\"message\":\"paid plan\"}");
        api.answer("/teams/1/matches", 500, "");
        assertThat(client("k").fetch("/competitions/CL/scorers", Map.of()).getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> broken = client("k").fetch("/teams/1/matches", Map.of());
        assertThat(broken.getStatusCode().value()).isEqualTo(500);
        assertThat(broken.getBody()).isEqualTo("{}");
    }

    @Test
    void aRateLimitIsWaitedOutAndRetriedOnce() {
        api.answer("/competitions/PL/matches", 429, "{}", Map.of("X-RequestCounter-Reset", "1"));
        api.answer("/competitions/PL/matches", 200, "{\"matches\":[]}");
        long started = System.currentTimeMillis();
        ResponseEntity<String> res = client("k").fetch("/competitions/PL/matches", FINISHED);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(api.count("/competitions/PL/matches")).isEqualTo(2);
        assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(900);
    }

    @Test
    void aSecondRateLimitIsReturnedRatherThanRetriedForever() {
        api.answer("/competitions/PL/matches", 429, "{\"message\":\"slow down\"}", Map.of("X-RequestCounter-Reset", "1"));
        assertThat(client("k").fetch("/competitions/PL/matches", FINISHED).getStatusCode().value()).isEqualTo(429);
        assertThat(api.count("/competitions/PL/matches")).isEqualTo(2);
    }

    @Test
    void callsOverThePlansLimitWaitForAFreeSlot() throws Exception {
        api.answer("/a", 200, "{}");
        api.answer("/b", 200, "{}");
        FootballDataClient c = new FootballDataClient("k", api.url(), 1);   // 1 call a minute
        c.fetch("/a", Map.of());

        Thread second = new Thread(() -> c.fetch("/b", Map.of()));
        second.start();
        Thread.sleep(400);
        assertThat(api.count("/b")).isZero();          // still waiting for the minute to pass
        assertThat(second.isAlive()).isTrue();

        second.interrupt();                             // stopping the app: it gives up waiting
        second.join(5_000);
        assertThat(second.isAlive()).isFalse();
    }

    @Test
    void withoutAKeyNothingIsSent() {
        ResponseEntity<String> res = client("").fetch("/competitions/PL/matches", FINISHED);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).contains("FOOTBALL_DATA_API_KEY");
        assertThat(new FootballDataClient(null, api.url(), 0).fetch("/x", Map.of()).getStatusCode().value()).isEqualTo(503);
        assertThat(api.requests).isEmpty();
    }

    @Test
    void paramsKeepTheirOrderAndIgnoreAnOddOneOut() {
        assertThat(FootballDataClient.params("b", "2", "a", "1", "x")).containsExactly(Map.entry("b", "2"), Map.entry("a", "1"));
    }
}
