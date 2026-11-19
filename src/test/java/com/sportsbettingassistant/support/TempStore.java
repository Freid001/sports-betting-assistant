package com.sportsbettingassistant.support;

import java.nio.file.Path;

import com.sportsbettingassistant.store.Store;

/** A fresh database in a test's temporary folder (close it after the test so the folder can be deleted). */
public final class TempStore {

    private TempStore() {
    }

    public static Store in(Path dir) {
        return new Store(dir.resolve("test.db").toString());
    }
}
