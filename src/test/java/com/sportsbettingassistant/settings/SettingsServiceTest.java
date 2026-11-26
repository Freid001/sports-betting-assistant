package com.sportsbettingassistant.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.football.live.GoalApiClient;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.FakeServer;
import com.sportsbettingassistant.support.TempStore;

class SettingsServiceTest {

    @TempDir
    Path tmp;
    Store store;
    SettingsService settings;

    @BeforeEach
    void setUp() {
        store = TempStore.in(tmp);
        settings = new SettingsService(store, tmp.resolve("no-such.env"));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    @Test
    void keysAreSavedShownOnlyInPartAndRemoved() {
        assertThat(settings.apiKey("odds-api")).isEmpty();
        assertThat(settings.keyStatus("odds-api")).containsEntry("set", false);

        settings.setApiKey("odds-api", "  pasted-key-abcd  ");
        assertThat(settings.apiKey("odds-api")).isEqualTo("pasted-key-abcd");
        assertThat(settings.keyStatus("odds-api")).containsEntry("set", true).containsEntry("ending", "abcd")
                .containsEntry("key", "pasted-key-abcd")   // for the Settings page's masked box
                .containsEntry("getKey", "https://dash.the-odds-api.com/api-subscriptions");

        settings.setApiKey("odds-api", "");
        assertThat(settings.apiKey("odds-api")).isEmpty();
    }

    @Test
    void readyOnlyWithBothKeys() {
        assertThat(settings.ready()).isFalse();
        settings.setApiKey("goal-api", "goal-key-5678");
        assertThat(settings.ready()).isFalse();
        settings.setApiKey("odds-api", "odds-key-1234");
        assertThat(settings.ready()).isTrue();
    }

    @Test
    void keysInAnOldDotEnvAreCopiedInOnce() throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "# old secrets\nODDS_API_KEY=from-env-1234\nGOAL_API_KEY = goal-env-5678\nOTHER=x\n");
        SettingsService first = new SettingsService(store, env);
        assertThat(first.apiKey("odds-api")).isEqualTo("from-env-1234");
        assertThat(first.apiKey("goal-api")).isEqualTo("goal-env-5678");

        first.setApiKey("odds-api", "");                        // removed on the Settings page...
        assertThat(new SettingsService(store, env).apiKey("odds-api")).isEmpty();   // ...stays removed: .env isn't read again
    }

    @Test
    void oddTextAndUnknownApisAreRefused() {
        assertThatThrownBy(() -> settings.setApiKey("goal-api", "has spaces in it")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.setApiKey("goal-api", "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.setApiKey("other", "abcdefghijk")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theThemeIsBlackUntilAnotherIsPicked() {
        assertThat(settings.theme().key()).isEqualTo("black");
        settings.setTheme("yellow");
        assertThat(settings.theme().color()).isEqualTo("#f2c12e");
        assertThat(settings.theme().text()).isEqualTo("#212529");   // dark text on a light colour
        assertThat(settings.theme().toMap()).containsEntry("ink", "#7a5c00");
        assertThat(new SettingsService(store, null).theme().key()).isEqualTo("yellow");   // kept
        assertThatThrownBy(() -> settings.setTheme("rainbow")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshOddsChoicesAreKept() {
        assertThat(settings.refreshOptions()).containsEntry("saved", false).containsEntry("matchResult", true)
                .containsEntry("scope", "off").containsEntry("markets", java.util.List.of());
        settings.setRefreshOptions(false, "favs", java.util.List.of("btts", "alternate_totals"));
        assertThat(new SettingsService(store, null).refreshOptions()).containsEntry("saved", true)
                .containsEntry("matchResult", false).containsEntry("scope", "favs")
                .containsEntry("markets", java.util.List.of("btts", "alternate_totals"));
        assertThatThrownBy(() -> settings.setRefreshOptions(true, "some", java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.setRefreshOptions(true, "all", java.util.List.of("x;y"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void liveRefreshIsFiveMinutesUntilAnotherIsPicked() {
        assertThat(settings.liveRefreshMinutes()).isEqualTo(5);
        settings.setLiveRefreshMinutes(2);
        assertThat(new SettingsService(store, null).liveRefreshMinutes()).isEqualTo(2);
        assertThatThrownBy(() -> settings.setLiveRefreshMinutes(3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void liveOddsAreOnUntilSwitchedOff() {
        assertThat(settings.liveOdds()).isTrue();
        settings.setLiveOdds(false);
        assertThat(new SettingsService(store, null).liveOdds()).isFalse();
    }

    @Test
    void theStakeBarIsOnUntilSwitchedOff() {
        assertThat(settings.stakeBar()).isTrue();
        settings.setStakeBar(false);
        assertThat(new SettingsService(store, null).stakeBar()).isFalse();
        settings.setStakeBar(true);
        assertThat(settings.stakeBar()).isTrue();
    }

    @Test
    void matchAnimationsAndSoundsAreOnUntilSwitchedOffEachOnItsOwn() {
        assertThat(settings.animations()).isTrue();
        assertThat(settings.sounds()).isTrue();
        settings.setSounds(false);
        assertThat(new SettingsService(store, null).sounds()).isFalse();
        assertThat(settings.animations()).isTrue();
        settings.setAnimations(false);
        settings.setSounds(true);
        assertThat(settings.animations()).isFalse();
        assertThat(settings.sounds()).isTrue();
    }

    @Test
    void clientsUseANewKeyWithoutARestart() {
        try (FakeServer api = new FakeServer()) {
            api.answer("/leagues", 200, "{\"data\":[]}");
            GoalApiClient goal = new GoalApiClient(api.url());
            goal.setSettings(settings);
            assertThat(goal.configured()).isFalse();
            settings.setApiKey("goal-api", "goal-key-5678");
            assertThat(goal.configured()).isTrue();
            goal.getJson("/leagues", java.util.Map.of());
            assertThat(api.last().header("Authorization")).isEqualTo("Bearer goal-key-5678");
        }
    }
}
