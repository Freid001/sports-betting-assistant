package com.sportsbettingassistant.store;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Where the database file is, as chosen on the Settings page (Storage). It can't be kept in the database it points
 * to, so it's kept in Java's per-user preferences: on Windows the registry
 * (HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\sportsbettingassistant, value store.file), on a Mac the
 * user's Java preferences (~/Library/Preferences), on Linux ~/.java/.userPrefs. Read at every start.
 */
public final class StorageLocation {

    /** Where the database goes until a location is chosen (relative to the folder the app runs in). */
    public static final String DEFAULT = "data/sports-betting-assistant.db";
    private static final String KEY = "store.file";

    private final Preferences prefs;

    /** {@code prefs}: where it's kept (tests use a throwaway node). */
    public StorageLocation(Preferences prefs) {
        this.prefs = prefs;
    }

    /** This computer user's location setting. */
    public static StorageLocation forUser() {
        return new StorageLocation(Preferences.userRoot().node("com/sportsbettingassistant"));
    }

    /** The database file to open: the one chosen, else the default. */
    public String current() {
        String s = saved();
        return s == null ? DEFAULT : s;
    }

    /** The location chosen on the Settings page, or null if none has been. */
    public String saved() {
        String v = prefs.get(KEY, null);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Remembers the database file's location (used from now on, and at every start). */
    public void save(String file) {
        prefs.put(KEY, file);
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Couldn't remember the database location: " + e.getMessage(), e);
        }
    }
}
