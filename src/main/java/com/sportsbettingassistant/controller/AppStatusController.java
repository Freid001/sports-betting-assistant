package com.sportsbettingassistant.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sportsbettingassistant.json.Json;

/**
 * GET /api/app/status: whether code has changed since the app started, so pages can show
 * "Restart needed". Pages and scripts (templates, static) reload on their own and don't count;
 * Java code, application.yaml and pom.xml need a restart.
 */
@RestController
public class AppStatusController {

    private static final List<Path> WATCH = List.of(
            Path.of("src", "main", "java"), Path.of("src", "main", "resources", "application.yaml"), Path.of("pom.xml"));

    private final List<Path> watch;
    private final Instant startedAt = Instant.now();

    public AppStatusController() {
        this(WATCH);
    }

    /** For tests: watch other files or folders. */
    AppStatusController(List<Path> watch) {
        this.watch = watch;
    }
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile List<String> changed = List.of();

    @GetMapping("/api/app/status")
    public ResponseEntity<String> status() {
        if (checkedAt.isBefore(Instant.now().minusSeconds(10))) {   // scan at most every 10 seconds
            changed = scan();
            checkedAt = Instant.now();
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("startedAt", startedAt.toString());
        out.put("restartNeeded", !changed.isEmpty());
        out.put("changed", changed);
        return Json.ok(out);
    }

    /** Files changed since start (names only, at most 5). */
    private List<String> scan() {
        List<String> out = new ArrayList<>();
        for (Path p : watch) {
            if (!Files.exists(p)) {
                continue;
            }
            try (Stream<Path> files = Files.isDirectory(p) ? Files.walk(p) : Stream.of(p)) {
                files.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        if (out.size() < 5 && Files.getLastModifiedTime(f).toInstant().isAfter(startedAt)) {
                            out.add(f.getFileName().toString());
                        }
                    } catch (IOException e) {
                        // ignore unreadable files
                    }
                });
            } catch (IOException e) {
                // ignore
            }
        }
        return out;
    }
}
