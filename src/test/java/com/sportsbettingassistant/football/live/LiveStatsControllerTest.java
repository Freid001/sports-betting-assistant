package com.sportsbettingassistant.football.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class LiveStatsControllerTest {

    @TempDir
    Path tmp;

    @Test
    void popUpLoadsGamesThenSavesTicks() {
        LiveServiceTest.FakeApi api = new LiveServiceTest.FakeApi();
        Store store = TempStore.in(tmp);
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("id", "g1");
        f.put("leagueId", "L1");
        f.put("kickoffUtc", Instant.now().plusSeconds(7200).toString());
        f.put("matchStatus", "SCHEDULED");
        f.put("matchLive", "0");
        api.answers.put("/leagues/L1/fixtures", Map.of("data", List.of(f)));
        LiveService service = new LiveService(api, new MockEnvironment().withProperty("goal-api.leagues.epl", "L1"),
                store, 5, 20, 50);
        LiveStatsController c = new LiveStatsController(service);

        assertThat(c.state().getBody()).contains("\"configured\":true").contains("\"fixtures\":[]");
        assertThat(c.fixtures(false).getBody()).contains("\"id\":\"g1\"");
        assertThat(c.track("{\"ids\":[\"g1\",\"unknown\"]}").getBody()).contains("\"tracked\":[\"g1\"]");
        assertThat(c.match("g1").getBody()).contains("\"tracked\":true").contains("\"stats\":null");
        assertThat(c.track("{}").getBody()).contains("\"tracked\":[]");
        store.close();
    }
}
