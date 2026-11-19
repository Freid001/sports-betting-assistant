package com.sportsbettingassistant.odds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.sportsbettingassistant.support.FakeServer;

class OddsApiClientTest {

    private final FakeServer api = new FakeServer();

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    void withoutAKeyNothingIsSent() {
        OddsApiClient client = new OddsApiClient("  ", api.url());
        ResponseEntity<String> res = client.get("/sports", Map.of());
        assertThat(client.hasKey()).isFalse();
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).contains("ODDS_API_KEY");
        assertThat(api.requests).isEmpty();
    }

    @Test
    void sendsTheKeyAndParamsAndPassesTheQuotaHeadersBack() {
        api.answer("/sports/soccer_epl/odds", 200, "[]",
                Map.of("x-requests-remaining", "481", "x-requests-used", "19", "x-requests-last", "2", "x-other", "no"));
        OddsApiClient client = new OddsApiClient(" secret ", api.url());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", " h2h ");
        params.put("bookmakers", "");     // blank params are left out
        params.put("regions", null);

        ResponseEntity<String> res = client.get("/sports/soccer_epl/odds", params);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("[]");
        assertThat(res.getHeaders().getFirst("x-requests-remaining")).isEqualTo("481");
        assertThat(res.getHeaders().getFirst("x-requests-last")).isEqualTo("2");
        assertThat(res.getHeaders().getFirst("x-other")).isNull();
        assertThat(api.last().query()).isEqualTo("markets=h2h&apiKey=secret");
    }

    @Test
    void errorsComeBackWithTheirStatus() {
        api.answer("/sports", 401, "{\"message\":\"bad key\"}");
        ResponseEntity<String> res = new OddsApiClient("k", api.url()).get("/sports", Map.of());
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody()).contains("bad key");
    }
}
