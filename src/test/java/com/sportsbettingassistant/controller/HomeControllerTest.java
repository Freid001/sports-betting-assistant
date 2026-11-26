package com.sportsbettingassistant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.settings.SettingsService;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class HomeControllerTest {

    @TempDir
    Path tmp;

    @Test
    void aNewUserGoesToSettingsThenHomeIsFootball() {
        try (Store store = TempStore.in(tmp)) {
            SettingsService settings = new SettingsService(store, null);
            HomeController home = new HomeController(settings);
            assertThat(home.index()).isEqualTo("redirect:/settings");   // no API keys yet
            settings.setApiKey("odds-api", "odds-key-1234");
            assertThat(home.index()).isEqualTo("redirect:/settings");   // both are needed
            settings.setApiKey("goal-api", "goal-key-5678");
            assertThat(home.index()).isEqualTo("redirect:/football");
            assertThat(home.horseRacing()).isEqualTo("horse-racing");
        }
    }
}
