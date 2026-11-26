package com.sportsbettingassistant.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OddsNamesTest {

    @Test
    void safeIdAcceptsSportKeysAndEventIds() {
        assertThat(OddsApiClient.safeId("soccer_epl")).isEqualTo("soccer_epl");
        assertThat(OddsApiClient.safeId("e912304de2b2ce35b473ce2ecd3d1502")).isEqualTo("e912304de2b2ce35b473ce2ecd3d1502");
    }

    @Test
    void safeIdRejectsAnythingThatCouldChangeTheUrl() {
        for (String bad : new String[] {"../odds", "a/b", "x?apiKey=1", "", " ", null}) {
            assertThatThrownBy(() -> OddsApiClient.safeId(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void footballNamesDropTheApisSoccerPrefix() {
        assertThat(OddsCacheService.footballName("soccer_uefa_champs_league")).isEqualTo("uefa_champs_league");
        assertThat(OddsCacheService.footballName("epl")).isEqualTo("epl");
    }
}
