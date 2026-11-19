package com.sportsbettingassistant.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.json.BasicJsonParser;

class JsonTest {

    @Test
    void writesNestedValuesAndEscapesText() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Say \"hi\"\nnow \\ ok");
        m.put("none", null);
        m.put("list", Arrays.asList(1, 2.5, true, null));
        m.put("inner", Map.of("a", "b"));
        assertThat(Json.write(m)).isEqualTo(
                "{\"name\":\"Say \\\"hi\\\"\\nnow \\\\ ok\",\"none\":null,\"list\":[1,2.5,true,null],\"inner\":{\"a\":\"b\"}}");
    }

    @Test
    void controlCharactersAreEscaped() {
        assertThat(Json.quote("a\u0001b")).isEqualTo("\"a\\u0001b\"");
    }

    @Test
    void outputCanBeReadBack() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teams", List.of(Map.of("name", "Tottenham Hotspur FC")));
        Map<String, Object> back = new BasicJsonParser().parseMap(Json.write(m));
        assertThat(back.get("teams")).isEqualTo(List.of(Map.of("name", "Tottenham Hotspur FC")));
    }

    @Test
    void setsAreWrittenAsListsAndTabsAndReturnsAreEscaped() {
        assertThat(Json.write(new java.util.LinkedHashSet<>(List.of("a", "b")))).isEqualTo("[\"a\",\"b\"]");
        assertThat(Json.quote("a\tb\rc")).isEqualTo("\"a\\tb\\rc\"");
    }

    @Test
    void readingDamagedOrMissingJsonGivesAnEmptyMap() {
        assertThat(Json.parse("{broken")).isEmpty();
        assertThat(Json.parse(null)).isEmpty();
        assertThat(Json.parse("{\"a\":1}")).containsEntry("a", 1L);
    }

    @Test
    void pickingValuesOutOfParsedJson() {
        assertThat(Json.map("not a map")).isEmpty();
        assertThat(Json.map(Map.of("a", 1))).containsEntry("a", 1);
        assertThat(Json.maps(List.of(Map.of("a", 1), "x", 2))).hasSize(1);
        assertThat(Json.maps("not a list")).isEmpty();
        assertThat(Json.text(null)).isNull();
        assertThat(Json.text("null")).isNull();
        assertThat(Json.text(5)).isEqualTo("5");
        assertThat(Json.number(7L)).isEqualTo(7);
        assertThat(Json.number(" 12 ")).isEqualTo(12);
        assertThat(Json.number("x")).isEqualTo(-1);
        assertThat(Json.yes(true)).isTrue();
        assertThat(Json.yes("true")).isTrue();
        assertThat(Json.yes("false")).isFalse();
        assertThat(Json.yes(null)).isFalse();
    }

    @Test
    void responsesAreJsonWithTheRightStatus() {
        var ok = Json.ok(Map.of("a", 1));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).isEqualTo("{\"a\":1}");
        assertThat(ok.getHeaders().getContentType().toString()).isEqualTo("application/json");
        var msg = Json.message(404, "Not \"here\"");
        assertThat(msg.getStatusCode().value()).isEqualTo(404);
        assertThat(msg.getBody()).isEqualTo("{\"message\":\"Not \\\"here\\\"\"}");
    }

    @Test
    void badInputAnywhereIsA400WithTheMessage() {
        var res = new ApiErrors().badRequest(new IllegalArgumentException("Invalid id: ../x"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).isEqualTo("{\"message\":\"Invalid id: ../x\"}");
    }
}
