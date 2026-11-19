package com.sportsbettingassistant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Restart needed" shows only when watched code changed after the app started. */
class AppStatusControllerTest {

    @TempDir
    Path tmp;

    @Test
    void nothingChangedMeansNoRestart() throws Exception {
        Files.writeString(tmp.resolve("Old.java"), "class Old {}");
        Files.setLastModifiedTime(tmp.resolve("Old.java"), FileTime.from(Instant.now().minusSeconds(3600)));
        String body = new AppStatusController(List.of(tmp, tmp.resolve("missing.yaml"))).status().getBody();
        assertThat(body).contains("\"restartNeeded\":false").contains("\"changed\":[]");
    }

    @Test
    void aChangedFileAsksForARestartAndSaysWhich() throws Exception {
        AppStatusController c = new AppStatusController(List.of(tmp));
        Path changed = Files.createDirectories(tmp.resolve("pkg")).resolve("LiveService.java");
        Files.writeString(changed, "class LiveService {}");
        Files.setLastModifiedTime(changed, FileTime.from(Instant.now().plusSeconds(5)));
        assertThat(c.status().getBody()).contains("\"restartNeeded\":true").contains("\"changed\":[\"LiveService.java\"]");
    }

    @Test
    void theDefaultWatchesTheProjectsCode() {
        assertThat(new AppStatusController().status().getBody()).contains("\"startedAt\"");
    }

    @Test
    void theFilesAreCheckedAtMostEveryTenSeconds() throws Exception {
        AppStatusController c = new AppStatusController(List.of(tmp));
        assertThat(c.status().getBody()).contains("\"restartNeeded\":false");
        Path changed = tmp.resolve("New.java");
        Files.writeString(changed, "class New {}");
        Files.setLastModifiedTime(changed, FileTime.from(Instant.now().plusSeconds(5)));
        assertThat(c.status().getBody()).contains("\"restartNeeded\":false");   // same answer until the next scan
    }
}
