package com.sportsbettingassistant.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.store.Store;

/**
 * Settings page: the API keys and the colour theme, saved in the settings table of the local database
 * (data/ is git-ignored, so keys never go to GitHub). Keys come only from here; a new one works at once, no
 * restart. The Settings page gets them back to fill its (masked) key boxes: this app only runs on your own computer.
 * <p>
 * Keys used to live in a .env file: on the first start after that changed, any found there are copied in here
 * once (the file isn't read again, and can be deleted).
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** An API the app needs a key for; envName is its old .env line (only for the one-time copy). */
    public record ApiKey(String name, String label, String envName, String getKey) {
    }

    /** A colour theme: banner, card and pop-up headers, main buttons; text is white, or dark on light colours. */
    public record Theme(String key, String name, String color, String hover, String rgb, String text, String textRgb) {

        /** Text on white in the theme colour (outline buttons): a darker shade for light colours like yellow. */
        public String ink() {
            return "#fff".equals(text) ? color : "#7a5c00";
        }

        /** As a map, for the pages (JSON and the page head). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("name", name);
            m.put("color", color);
            m.put("hover", hover);
            m.put("rgb", rgb);
            m.put("text", text);
            m.put("textRgb", textRgb);
            m.put("ink", ink());
            return m;
        }
    }

    public static final List<ApiKey> KEYS = List.of(
            new ApiKey("odds-api", "The Odds API", "ODDS_API_KEY", "https://dash.the-odds-api.com/api-subscriptions"),
            new ApiKey("goal-api", "GOAL API", "GOAL_API_KEY", "https://goal-api.com/dashboard/api-keys"));

    public static final List<Theme> THEMES = List.of(
            new Theme("black", "Black", "#1f2326", "#3a3f45", "31,35,38", "#fff", "255,255,255"),          // the default
            new Theme("burgundy", "Burgundy", "#6b1f2e", "#8c2a3d", "107,31,46", "#fff", "255,255,255"),
            new Theme("blue", "Royal Blue", "#1f4e8c", "#2a63ab", "31,78,140", "#fff", "255,255,255"),
            new Theme("green", "Racing Green", "#1e5631", "#2a7343", "30,86,49", "#fff", "255,255,255"),
            new Theme("yellow", "Sunflower", "#f2c12e", "#e0ad14", "242,193,46", "#212529", "33,37,41"),
            new Theme("purple", "Amethyst", "#4b2a6b", "#623a8a", "75,42,107", "#fff", "255,255,255"),
            new Theme("orange", "Tangerine", "#d9661f", "#bf5410", "217,102,31", "#fff", "255,255,255"));

    private static final Pattern KEY_TEXT = Pattern.compile("[A-Za-z0-9._\\-]{8,200}");
    private static final String IMPORTED = "dotenv-keys-imported";

    private final Store store;
    private volatile Theme theme;   // read on every page, so kept in memory

    @Autowired
    public SettingsService(Store store) {
        this(store, Path.of(".env"));
    }

    /** {@code dotEnv}: the old secrets file, copied in once if it exists. */
    public SettingsService(Store store, Path dotEnv) {
        this.store = store;
        importDotEnvOnce(dotEnv);
    }

    // ---------------------------------------------------------------- API keys

    /** The key saved for an API, or "" if none. */
    public String apiKey(String name) {
        return saved("key/" + find(name).name()).orElse("");
    }

    /** Both keys saved: the app can work. Without them it's a new user, who gets the Settings page. */
    public boolean ready() {
        return keysSet() && (storage == null || storage.chosen());
    }

    /** Both API keys saved. */
    public boolean keysSet() {
        return KEYS.stream().allMatch(k -> !apiKey(k.name()).isEmpty());
    }

    private StorageService storage;   // set up = a storage location chosen and both keys saved

    @Autowired(required = false)
    public void setStorage(StorageService storage) {
        this.storage = storage;
    }

    /** Saves a key (blank removes it). */
    public void setApiKey(String name, String value) {
        ApiKey k = find(name);
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            store.update("DELETE FROM settings WHERE name = ?", "key/" + k.name());
            return;
        }
        if (!KEY_TEXT.matcher(v).matches()) {
            throw new IllegalArgumentException("That doesn't look like an API key (letters, numbers and - _ . only).");
        }
        save("key/" + k.name(), v);
    }

    /** What the Settings page shows for a key, including the key itself for its masked box (Show reveals it). */
    public Map<String, Object> keyStatus(String name) {
        ApiKey k = find(name);
        String key = apiKey(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", k.name());
        out.put("label", k.label());
        out.put("getKey", k.getKey());
        out.put("set", !key.isEmpty());
        out.put("key", key);
        out.put("ending", key.length() >= 4 ? key.substring(key.length() - 4) : null);
        // first and last 4 ("7f3a…46f6"), so you can tell keys apart; never more than 8 characters of it
        out.put("preview", key.length() >= 12 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : key.length() >= 4 ? "…" + key.substring(key.length() - 4) : null);
        return out;
    }

    public static ApiKey find(String name) {
        return KEYS.stream().filter(k -> k.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown API: " + name));
    }

    /** Keys used to be in .env: copies any found there (once; the file isn't read again). */
    private void importDotEnvOnce(Path dotEnv) {
        if (saved(IMPORTED).isPresent()) {
            return;
        }
        if (dotEnv != null && Files.isRegularFile(dotEnv)) {
            try {
                for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0 || line.strip().startsWith("#")) {
                        continue;
                    }
                    String envName = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
                    for (ApiKey k : KEYS) {
                        if (k.envName().equals(envName) && apiKey(k.name()).isEmpty() && KEY_TEXT.matcher(value).matches()) {
                            setApiKey(k.name(), value);
                            log.info("Copied {} from .env to Settings (.env is no longer used)", k.label());
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Couldn't read .env: {}", e.getMessage());
            }
        }
        save(IMPORTED, "yes");
    }

    // ---------------------------------------------------------------- theme

    public Theme theme() {
        Theme t = theme;
        if (t == null) {
            String key = saved("theme").orElse("black");
            t = THEMES.stream().filter(x -> x.key().equals(key)).findFirst().orElse(THEMES.get(0));
            theme = t;
        }
        return t;
    }

    public void setTheme(String key) {
        if (THEMES.stream().noneMatch(t -> t.key().equals(key))) {
            throw new IllegalArgumentException("Unknown theme: " + key);
        }
        save("theme", key);
        theme = null;
    }

    // ---------------------------------------------------------------- Refresh Odds

    /** Which matches get the extra markets: none, favourite teams' matches, or every match in the next 30 days. */
    public static final List<String> SCOPES = List.of("off", "favs", "all");
    private static final Pattern MARKET = Pattern.compile("[a-z0-9_]{1,60}");

    /**
     * What Refresh Odds fetches (the bookmaker is a setting of its own, shared with live odds):
     * {saved, matchResult, scope, markets}. saved is false until they're first saved.
     */
    public Map<String, Object> refreshOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved("refresh-scope").isPresent());
        out.put("matchResult", isOn("refresh-match-result"));
        out.put("scope", saved("refresh-scope").filter(SCOPES::contains).orElse("off"));
        out.put("markets", saved("refresh-markets").map(m -> List.of(m.split(","))).orElse(List.of()));
        return out;
    }

    public void setRefreshOptions(boolean matchResult, String scope, List<String> markets) {
        if (!SCOPES.contains(scope)) {
            throw new IllegalArgumentException("Unknown scope: " + scope);
        }
        for (String m : markets) {
            if (!MARKET.matcher(m).matches()) {
                throw new IllegalArgumentException("Bad market: " + m);
            }
        }
        store.transaction(() -> {
            saveOn("refresh-match-result", matchResult);
            save("refresh-scope", scope);
            save("refresh-markets", String.join(",", markets));
        });
    }

    // ---------------------------------------------------------------- Refresh Data Live

    /** How often followed games are refreshed while they're on, in minutes; 0: Off (not refreshed at all). */
    public static final List<Integer> LIVE_REFRESH = List.of(1, 2, 5, 10, 0);

    public int liveRefreshMinutes() {
        try {
            int m = Integer.parseInt(saved("live-refresh").orElse("5"));
            return LIVE_REFRESH.contains(m) ? m : 5;
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    public void setLiveRefreshMinutes(int minutes) {
        if (!LIVE_REFRESH.contains(minutes)) {
            throw new IllegalArgumentException("Refresh Data Live is 1, 2, 5 or 10 minutes, or 0 for Off");
        }
        save("live-refresh", String.valueOf(minutes));
    }

    /** Refresh Odds Live: the bookmaker's in-play odds at each timeline point of a followed game (on unless off). */
    public boolean liveOdds() {
        return isOn("live-odds");
    }

    public void setLiveOdds(boolean on) {
        saveOn("live-odds", on);
    }

    /** The bet windows there are, in match order. */
    public static final List<String> BET_WINDOWS = List.of("30", "HT", "60", "80");

    /** Bet Windows: which of them fetch the in-play odds (Refresh Odds Live); all of them until picked. */
    public List<String> betWindows() {
        String v = saved("bet-windows").orElse(null);
        if (v == null) {
            return BET_WINDOWS;
        }
        List<String> picked = List.of(v.split(","));
        return BET_WINDOWS.stream().filter(picked::contains).toList();
    }

    public void setBetWindows(List<String> windows) {
        for (String w : windows) {
            if (!BET_WINDOWS.contains(w)) {
                throw new IllegalArgumentException("Bet windows are 30, HT, 60 and 80: not " + w);
            }
        }
        save("bet-windows",
                String.join(",", BET_WINDOWS.stream().filter(windows::contains).toList()));
    }

    // ---------------------------------------------------------------- top bar

    /** Stake Bar: the £ stake buttons in the top bar (on unless switched off). */
    public boolean stakeBar() {
        return isOn("stake-bar");
    }

    public void setStakeBar(boolean on) {
        saveOn("stake-bar", on);
    }

    // ---------------------------------------------------------------- match page alerts

    /** Animations: the GOAL!, card, penalty, kick-off / HT / FT banners and the bet window note (on unless off). */
    public boolean animations() {
        return isOn("match-animations");
    }

    public void setAnimations(boolean on) {
        saveOn("match-animations", on);
    }

    /** Sounds: the crowd, whistles and the bet window chime on the match page (on unless off). */
    public boolean sounds() {
        return isOn("match-sounds");
    }

    public void setSounds(boolean on) {
        saveOn("match-sounds", on);
    }

    /** Saves one setting (replacing what was there). */
    private void save(String name, String value) {
        store.update("INSERT OR REPLACE INTO settings (name, value) VALUES (?, ?)", name, value);
    }

    /** An On / Off setting: on unless it's been switched off. */
    private boolean isOn(String name) {
        return !"off".equals(saved(name).orElse("on"));
    }

    private void saveOn(String name, boolean on) {
        save(name, on ? "on" : "off");
    }

    private Optional<String> saved(String name) {
        return store.first("SELECT value FROM settings WHERE name = ?", rs -> rs.getString(1), name)
                .filter(v -> !v.isBlank());
    }
}
