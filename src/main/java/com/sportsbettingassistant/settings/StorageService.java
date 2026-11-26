package com.sportsbettingassistant.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sportsbettingassistant.store.StorageLocation;
import com.sportsbettingassistant.store.Store;

/**
 * Settings page's Storage card (step 1 of setting up the app): which database file everything is kept in, the
 * settings themselves included.
 * <p>
 * The path of the .db file is remembered outside that file, in Java's per-user preferences (the Windows registry;
 * see StorageLocation), and read at startup. Until a location is chosen the app counts as not set up: the other
 * pages stay hidden and Settings asks for it first.
 * <p>
 * Choosing a new file copies the data there (a consistent copy of the live database; the old file is left as it
 * is) and carries on in it straight away. Choosing a database that's already there is used as it is, from the next
 * start (the app's in-memory state has to be read from it).
 */
@Service
public class StorageService {

    private final Store store;
    private final StorageLocation location;

    @org.springframework.beans.factory.annotation.Autowired
    public StorageService(Store store) {
        this(store, StorageLocation.forUser());
    }

    /** {@code location}: where the choice is remembered (tests use a throwaway one). */
    StorageService(Store store, StorageLocation location) {
        this.store = store;
        this.location = location;
    }

    /** A location has been chosen on the Settings page (a new install asks for one first). */
    public boolean chosen() {
        return saved() != null;
    }

    /**
     * {file (in use now), size, modified, chosen, next (an existing database picked, used from the next start),
     * suggested (a path to offer when nothing's chosen yet)}.
     */
    public Map<String, Object> status() {
        Path now = store.file() == null ? null : store.file().toAbsolutePath().normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", now == null ? null : now.toString());   // null: in memory, nothing saved to a file yet
        out.put("size", now == null ? -1 : size(now));
        out.put("modified", now == null ? null : modified(now));
        String saved = saved();
        out.put("chosen", saved != null);
        Path next = saved == null ? now : Path.of(saved).toAbsolutePath().normalize();
        out.put("next", next == null || next.equals(now) ? null : next.toString());
        out.put("suggested", Path.of(System.getProperty("user.home"), "Documents", "Sports Betting Assistant",
                "sports-betting-assistant.db").toString());
        return out;
    }

    /**
     * Keeps everything in this .db file from now on. The file in use: just remembered. A new file: the data is copied
     * there and the app carries on in it now. An existing database: used as it is from the next start.
     * Returns {file, copied, existing, restart}.
     */
    public Map<String, Object> choose(String to) {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Type the full path of the database file, ending in .db.");
        }
        Path target;
        try {
            target = Path.of(to.trim().replace("\"", "")).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("That isn't a valid file path: " + e.getMessage());
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("That's a folder: add a file name ending in .db, e.g. "
                    + target.resolve("sports-betting-assistant.db"));
        }
        String name = target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3"))) {
            throw new IllegalArgumentException("Give the full path of the database file, ending in .db.");
        }
        Path now = store.file() == null ? null : store.file().toAbsolutePath().normalize();
        boolean same = target.equals(now);
        boolean existing = !same && Files.exists(target);
        boolean copied = false;
        if (!same && !existing) {
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new IllegalArgumentException("Couldn't make the folder " + target.getParent() + ": " + e.getMessage());
            }
            store.update("VACUUM INTO ?", target.toString());   // a consistent copy of the live database
            copied = true;
        }
        location.save(target.toString());
        if (copied) {
            store.switchTo(target);   // same data, so the app carries on in it with no restart
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", target.toString());
        out.put("copied", copied);
        out.put("existing", existing);
        out.put("restart", existing);
        out.put("old", copied && now != null ? now.toString() : null);
        return out;
    }

    /**
     * Deletes all the app's data: the database file is replaced by an empty one at the same location (which stays
     * chosen). Keys, settings, bets, favourites, odds and stats all go. Can't be undone.
     */
    public Map<String, Object> delete() {
        store.wipe();
        return status();
    }

    /** The location remembered, or null if none has been chosen. */
    String saved() {
        return location.saved();
    }

    private static long size(Path p) {
        try {
            long n = Files.size(p);
            Path wal = Path.of(p + "-wal");
            return n + (Files.exists(wal) ? Files.size(wal) : 0);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String modified(Path p) {
        try {
            Instant a = Files.getLastModifiedTime(p).toInstant();
            Path wal = Path.of(p + "-wal");
            if (Files.exists(wal)) {
                Instant b = Files.getLastModifiedTime(wal).toInstant();
                a = b.isAfter(a) ? b : a;
            }
            return a.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
