package com.sportsbettingassistant.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sportsbettingassistant.store.StorageLocation;
import com.sportsbettingassistant.store.Store;
import com.sportsbettingassistant.support.TempStore;

class StorageServiceTest {

    @TempDir
    Path tmp;
    Store store;
    StorageService storage;
    java.util.prefs.Preferences prefs;
    StorageLocation location;

    @BeforeEach
    void setUp() {
        store = TempStore.in(tmp);
        prefs = java.util.prefs.Preferences.userRoot().node("com/sportsbettingassistant/test-" + java.util.UUID.randomUUID());
        location = new StorageLocation(prefs);
        storage = new StorageService(store, location);
    }

    @AfterEach
    void stop() throws Exception {
        store.close();
        prefs.removeNode();
    }

    @Test
    void nothingIsChosenAtFirstSoTheAppIsNotSetUp() {
        Map<String, Object> st = storage.status();
        assertThat(st.get("file")).isEqualTo(tmp.resolve("test.db").toAbsolutePath().normalize().toString());
        assertThat((long) st.get("size")).isPositive();
        assertThat(st).containsEntry("chosen", false);
        SettingsService settings = new SettingsService(store, tmp.resolve("no.env"));
        settings.setStorage(storage);
        settings.setApiKey("odds-api", "abcdefgh1234");
        settings.setApiKey("goal-api", "ijklmnop5678");
        assertThat(settings.keysSet()).isTrue();
        assertThat(settings.ready()).isFalse();   // storage first
        storage.choose(st.get("file").toString());   // Keep it here
        assertThat(storage.chosen()).isTrue();
        assertThat(settings.ready()).isTrue();
        assertThat(storage.status()).containsEntry("next", null);
    }

    @Test
    void aNewFileGetsACopyOfTheDataAndIsUsedStraightAway() throws Exception {
        SettingsService settings = new SettingsService(store, tmp.resolve("no.env"));
        settings.setTheme("purple");
        Path target = tmp.resolve("elsewhere").resolve("mine.db");

        Map<String, Object> r = storage.choose(target.toString());

        assertThat(r).containsEntry("copied", true).containsEntry("restart", false);
        assertThat(store.file()).isEqualTo(target);                  // carrying on in the new file
        settings.setTheme("yellow");                                  // ...so this goes there
        try (Store copy = new Store(target.toString())) {
            assertThat(new SettingsService(copy, tmp.resolve("no.env")).theme().key()).isEqualTo("yellow");
        }
        try (Store old = new Store(tmp.resolve("test.db").toString())) {   // the old file is kept as it was
            assertThat(new SettingsService(old, tmp.resolve("no.env")).theme().key()).isEqualTo("purple");
        }
        assertThat(location.saved()).isEqualTo(target.toString());   // remembered for every start
    }

    @Test
    void anExistingDatabaseIsUsedAsItIsFromTheNextStart() throws Exception {
        Path backup = tmp.resolve("backup.db");
        try (Store other = new Store(backup.toString())) {
            new SettingsService(other, tmp.resolve("no.env")).setTheme("yellow");
        }
        assertThat(storage.choose(backup.toString())).containsEntry("copied", false).containsEntry("existing", true)
                .containsEntry("restart", true);
        assertThat(storage.status().get("next")).isEqualTo(backup.toAbsolutePath().normalize().toString());
        try (Store other = new Store(backup.toString())) {   // not overwritten
            assertThat(new SettingsService(other, tmp.resolve("no.env")).theme().key()).isEqualTo("yellow");
        }
    }

    @Test
    void deletingWipesEverythingButKeepsTheLocation() {
        SettingsService settings = new SettingsService(store, tmp.resolve("no.env"));
        settings.setApiKey("odds-api", "abcdefgh1234");
        settings.setTheme("purple");
        storage.choose(store.file().toString());

        storage.delete();

        assertThat(settings.apiKey("odds-api")).isEmpty();
        assertThat(settings.theme().key()).isNotEqualTo("purple");
        assertThat(storage.chosen()).isTrue();
        settings.setTheme("yellow");   // the empty database works
        assertThat(settings.theme().key()).isEqualTo("yellow");
    }

    @Test
    void onlyADatabaseFilePathIsTaken() {
        assertThatThrownBy(() -> storage.choose(tmp.resolve("notes.txt").toString())).hasMessageContaining(".db");
        assertThatThrownBy(() -> storage.choose(tmp.toString())).hasMessageContaining("folder");
        assertThatThrownBy(() -> storage.choose(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(location.saved()).isNull();
    }

    @Test
    void aNewInstallKeepsEverythingInMemoryUntilALocationIsSaved() throws Exception {
        try (Store memory = new Store((Path) null)) {
            StorageService first = new StorageService(memory, location);
            new SettingsService(memory, tmp.resolve("no.env")).setTheme("purple");   // e.g. set up before Storage
            assertThat(first.status()).containsEntry("file", null).containsEntry("chosen", false);
            Path target = tmp.resolve("docs").resolve("mine.db");

            assertThat(first.choose(target.toString())).containsEntry("copied", true).containsEntry("restart", false);

            assertThat(memory.file()).isEqualTo(target);   // carrying on in the file now
            assertThat(location.saved()).isEqualTo(target.toString());
            try (Store saved = new Store(target)) {
                assertThat(new SettingsService(saved, tmp.resolve("no.env")).theme().key()).isEqualTo("purple");
            }
        }
    }

    @Test
    void theLocationIsKeptInThePreferencesForEveryStart() {
        assertThat(location.current()).isEqualTo(StorageLocation.DEFAULT);   // nothing chosen yet
        location.save("C:\\Users\\me\\Documents\\x.db");
        assertThat(new StorageLocation(prefs).current()).isEqualTo("C:\\Users\\me\\Documents\\x.db");
    }
}
