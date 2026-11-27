package com.sportsbettingassistant.odds;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.json.Json;
import com.sportsbettingassistant.store.Store;

/**
 * Saves whole-tournament odds pulls in the odds tables (odds_fetches, odds_events, odds_prices) so the app
 * can read them again without spending credits. Only a refresh calls The Odds API.
 */
@Service
public class OddsCacheService {

    private static final Pattern SAFE_LIST = Pattern.compile("[a-z0-9_,]*");

    /** Markets that come with Refresh Odds (one request per competition). */
    static final List<String> BULK_MARKETS = List.of("h2h", "totals", "spreads");
    /** Used if odds-api.bookmakers isn't set: Sky Bet and the extra markets it has through The Odds API. */
    private static final Map<String, String> DEFAULT_BOOKMAKERS = Map.of("skybet",
            "alternate_totals,btts,h2h_3_way_h1,halftime_fulltime,player_goal_scorer_anytime,player_first_goal_scorer");

    private final OddsApiClient client;
    private final Store store;
    private final List<String> tournaments;
    /** Bookmakers you can pick (odds-api.bookmakers) and the extra markets each offers, in settings order. */
    private final Map<String, List<String>> bookmakerMarkets;
    /** Credits left this month, from The Odds API's last answer (-1 until we've seen one). */
    private volatile long creditsLeft = -1;

    private final com.sportsbettingassistant.football.Games games;

    public OddsCacheService(OddsApiClient client, Store store, Environment env) {
        this.client = client;
        this.store = store;
        this.games = new com.sportsbettingassistant.football.Games(store);
        Map<String, String> books = Binder.get(env).bind("odds-api.bookmakers", Bindable.mapOf(String.class, String.class))
                .orElse(DEFAULT_BOOKMAKERS);
        Map<String, List<String>> markets = new LinkedHashMap<>();
        books.forEach((book, list) -> markets.put(OddsApiClient.safeId(book), List.of(checkList(list, "extra markets").split(","))));
        this.bookmakerMarkets = java.util.Collections.unmodifiableMap(markets);
        // football.tournaments is a YAML list in application.yaml
        List<String> footballTournaments = Binder.get(env)
                .bind("football.tournaments", Bindable.listOf(String.class)).orElse(List.of());
        this.tournaments = footballTournaments.stream().map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> FOOTBALL_API_PREFIX + OddsApiClient.safeId(t)).toList();
    }

    /** The Odds API calls football "soccer"; we only use that word when talking to the API. */
    public static final String FOOTBALL_API_PREFIX = "soccer_";

    /** Our name for an API sport key, e.g. soccer_uefa_champs_league -> uefa_champs_league. */
    public static String footballName(String sportKey) {
        return sportKey.startsWith(FOOTBALL_API_PREFIX) ? sportKey.substring(FOOTBALL_API_PREFIX.length()) : sportKey;
    }

    /** The configured football tournaments as API sport keys (football.tournaments). */
    public List<String> tournaments() {
        return tournaments;
    }

    /**
     * Refreshes every configured tournament that currently has fixtures. The in-season check
     * (/sports) is free, so inactive tournaments cost nothing. Returns a JSON summary.
     */
    public String refreshAll(String markets, String bookmakers, int days) {
        ResponseEntity<String> sports = client.get("/sports", Map.of());
        if (!sports.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Could not load the sports list: " + sports.getBody());
        }
        String active = sports.getBody() == null ? "" : sports.getBody();
        StringBuilder out = new StringBuilder("[");
        for (String sport : tournaments) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append("{\"sport\":\"").append(sport).append("\",");
            if (!active.contains("\"key\":\"" + sport + "\"")) {
                out.append("\"status\":\"inactive\",\"credits\":0}");
                continue;
            }
            ResponseEntity<String> res = refresh(sport, markets, bookmakers, days);
            String cost = Optional.ofNullable(res.getHeaders().getFirst("x-requests-last")).orElse("0")
                    .replaceAll("[^0-9]", "");
            if (res.getStatusCode().is2xxSuccessful()) {
                int events = eventCount(sport);
                out.append("\"status\":\"refreshed\",\"events\":").append(events)
                        .append(",\"credits\":").append(cost.isEmpty() ? "0" : cost).append('}');
            } else {
                out.append("\"status\":\"error ").append(res.getStatusCode().value())
                        .append("\",\"credits\":0}");
            }
        }
        return out.append(']').toString();
    }

    /**
     * One API call for every event in the sport starting within {@code days}.
     * Cost = number of markets x regions (up to 10 bookmakers count as one region).
     */
    public ResponseEntity<String> refresh(String sport, String markets, String bookmakers, int days) {
        OddsApiClient.safeId(sport);
        markets = checkList(markets, "markets");
        bookmakers = checkList(bookmakers, "bookmakers");
        if (days < 1 || days > 60) {
            throw new IllegalArgumentException("days must be between 1 and 60");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", markets);
        params.put("bookmakers", bookmakers);
        params.put("oddsFormat", "decimal");
        params.put("dateFormat", "iso");
        params.put("commenceTimeFrom", now.toString());
        params.put("commenceTimeTo", now.plus(days, ChronoUnit.DAYS).toString());

        ResponseEntity<String> res = client.get("/sports/" + sport + "/odds", params);
        remember(res);
        if (!res.getStatusCode().is2xxSuccessful()) {
            return res;
        }
        String cost = Optional.ofNullable(res.getHeaders().getFirst("x-requests-last")).orElse("").replaceAll("[^0-9]", "");
        save(sport, now.toString(), days, markets, bookmakers, cost, res.getBody());
        return ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders()).body(read(sport).orElse("{}"));
    }

    /** A free call (/sports) whose response headers carry the credits used and remaining. */
    public ResponseEntity<String> quota() {
        return client.get("/sports", Map.of());
    }

    // ---------------------------------------------------------------- tables: odds_fetches, odds_events, odds_prices

    /**
     * Saves one Refresh Odds of a sport ({@code eventsJson} is The Odds API's array): updates its matches and replaces
     * their match-result prices. Extra markets fetched per match are kept; matches no longer listed (started or
     * finished) are removed with everything saved for them.
     */
    public void save(String sport, String fetchedAt, int days, String markets, String bookmakers, String credits,
                     String eventsJson) {
        List<Map<String, Object>> events = Json.maps(Json.parse("{\"e\":" + eventsJson + "}").get("e"));
        java.util.Set<String> ids = new java.util.HashSet<>();
        events.forEach(e -> ids.add(Json.text(e.get("id"))));
        java.util.Set<String> replaced = new java.util.HashSet<>(BULK_MARKETS);
        // Only this refresh's bookmakers are replaced: another bookmaker's saved prices are kept
        java.util.Set<String> replacedBooks = new java.util.HashSet<>(List.of(checkList(bookmakers, "bookmakers").split(",")));
        if (markets != null) {
            replaced.addAll(List.of(markets.split(",")));
        }
        store.transaction(() -> {
            for (String old : store.query("SELECT id FROM odds_events WHERE sport = ?", rs -> rs.getString(1), sport)) {
                if (!ids.contains(old)) {
                    deleteEvent(old);
                }
            }
            for (Map<String, Object> e : events) {
                String id = Json.text(e.get("id"));
                if (id == null) {
                    continue;
                }
                store.update("INSERT INTO odds_events (id, sport, sport_title, kickoff, home_team, away_team) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET sport = excluded.sport, sport_title = excluded.sport_title, "
                        + "kickoff = excluded.kickoff, home_team = excluded.home_team, away_team = excluded.away_team",
                        id, sport, Json.text(e.get("sport_title")), Json.text(e.get("commence_time")),
                        Json.text(e.get("home_team")), Json.text(e.get("away_team")));
                savePrices(id, Json.maps(e.get("bookmakers")), replaced, replacedBooks);
            }
            store.update("INSERT OR REPLACE INTO odds_fetches (sport, fetched_at_iso, days, markets, bookmakers, credits) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", sport, fetchedAt, days, markets, bookmakers, credits);
        });
        games.linkOddsEvents();   // new events: each to its game (once)
    }

    /**
     * Saves a match's prices: first removes the prices of the markets in {@code replace} (and of any market in the
     * answer), so other markets saved earlier stay. Returns the markets that came back.
     */
    private java.util.Set<String> savePrices(String eventId, List<Map<String, Object>> bookmakers, java.util.Set<String> replace,
                                             java.util.Set<String> books) {
        java.util.Set<String> returned = new java.util.TreeSet<>();
        bookmakers.forEach(b -> Json.maps(b.get("markets")).forEach(m -> {
            if (!Json.maps(m.get("outcomes")).isEmpty() && Json.text(m.get("key")) != null) {
                returned.add(Json.text(m.get("key")));
            }
        }));
        java.util.Set<String> remove = new java.util.TreeSet<>(replace);
        remove.addAll(returned);
        for (String market : remove) {
            for (String book : books) {
                store.update("DELETE FROM odds_prices WHERE event_id = ? AND market = ? AND bookmaker = ?", eventId, market, book);
            }
        }
        int position = store.first("SELECT COALESCE(MAX(position) + 1, 0) FROM odds_prices WHERE event_id = ?",
                rs -> rs.getInt(1), eventId).orElse(0);
        for (Map<String, Object> b : bookmakers) {
            for (Map<String, Object> m : Json.maps(b.get("markets"))) {
                for (Map<String, Object> o : Json.maps(m.get("outcomes"))) {
                    store.update("INSERT INTO odds_prices (event_id, position, bookmaker, bookmaker_title, market, "
                            + "outcome, description, point, price, last_update) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            eventId, position++, Json.text(b.get("key")), Json.text(b.get("title")), Json.text(m.get("key")),
                            Json.text(o.get("name")), Json.text(o.get("description")), decimal(o.get("point")),
                            decimal(o.get("price")), Json.text(m.get("last_update")));
                }
            }
        }
        return returned;
    }

    private void deleteEvent(String eventId) {
        store.update("DELETE FROM odds_prices WHERE event_id = ?", eventId);
        store.update("DELETE FROM odds_extra_fetches WHERE event_id = ?", eventId);
        store.update("DELETE FROM odds_events WHERE id = ?", eventId);
    }

    // ---------------------------------------------------------------- all markets, one request per match

    /** The extra markets asked for per match (application.yaml odds-api.extra-markets). */
    public Map<String, List<String>> extraMarkets() {
        return bookmakerMarkets;
    }

    /**
     * The bookmaker picked in Refresh Odds (Sky Bet until another is picked). One choice for the whole app: the pages
     * show its prices and the live odds at each point are fetched from it.
     */
    public String bookmaker() {
        return store.first("SELECT value FROM settings WHERE name = 'bookmaker'", rs -> rs.getString(1))
                .filter(bookmakerMarkets::containsKey).orElse("skybet");
    }

    /** Picks the bookmaker (one listed in odds-api.bookmakers). */
    public String setBookmaker(String key) {
        if (!bookmakerMarkets.containsKey(key)) {
            throw new IllegalArgumentException("Unknown bookmaker: " + key);
        }
        store.update("INSERT OR REPLACE INTO settings (name, value) VALUES ('bookmaker', ?)", key);
        return key;
    }

    /** The extra markets one bookmaker offers (odds-api.bookmakers); an error for a bookmaker not listed there. */
    public List<String> extraMarkets(String bookmaker) {
        List<String> markets = bookmakerMarkets.get(bookmaker);
        if (markets == null) {
            throw new IllegalArgumentException("Pick one of these bookmakers: " + String.join(", ", bookmakerMarkets.keySet()));
        }
        return markets;
    }

    /**
     * Extra markets: one request per saved match kicking off within {@code days}, asking for every extra market the
     * bookmaker offers. The Odds API charges 1 credit per market it returns for that match. Uses every credit left:
     * stops only when none are left (or The Odds API refuses). Returns {sport, matches, credits, markets, stopped}.
     */
    public Map<String, Object> refreshExtras(String sport, int days, String bookmaker) {
        return refreshExtras(sport, days, bookmaker, extraMarkets(bookmaker));
    }

    /**
     * The same, for just the ticked markets (each must be one of odds-api.extra-markets). Only those markets'
     * saved prices are replaced, so markets fetched earlier and not ticked this time are kept.
     */
    public Map<String, Object> refreshExtras(String sport, int days, String bookmaker, List<String> markets) {
        return refreshExtras(sport, days, bookmaker, markets, null);
    }

    /** The same, for just the matches with these event ids (e.g. ones a favourite team plays in); null means all. */
    public Map<String, Object> refreshExtras(String sport, int days, String bookmaker, List<String> markets,
                                             java.util.Collection<String> onlyIds) {
        OddsApiClient.safeId(sport);
        if (onlyIds != null) {
            onlyIds.forEach(OddsApiClient::safeId);
        }
        List<String> offered = extraMarkets(bookmaker);
        if (markets == null || markets.isEmpty() || !offered.containsAll(markets)) {
            throw new IllegalArgumentException("Pick one or more of: " + String.join(", ", offered));
        }
        if (days < 1 || days > 60) {
            throw new IllegalArgumentException("days must be between 1 and 60");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<String> ids = store.query("SELECT id FROM odds_events WHERE sport = ? AND kickoff >= ? AND kickoff <= ? ORDER BY kickoff",
                rs -> rs.getString(1), sport, now.toString(), now.plus(days, ChronoUnit.DAYS).toString());
        if (onlyIds != null) {
            ids = ids.stream().filter(onlyIds::contains).toList();
        }
        if (creditsLeft < 0 && !ids.isEmpty()) {
            remember(quota());   // free call, just to know how many credits are left
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> returned = new java.util.TreeMap<>();
        int matches = 0, credits = 0, errors = 0;
        String stopped = null, lastError = null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", String.join(",", markets));
        params.put("bookmakers", bookmaker);
        params.put("oddsFormat", "decimal");
        params.put("dateFormat", "iso");
        for (String id : ids) {
            if (creditsLeft == 0) {
                stopped = "Stopped: no credits left this month.";
                break;
            }
            ResponseEntity<String> res = client.get("/sports/" + sport + "/events/" + OddsApiClient.safeId(id) + "/odds", params);
            remember(res);
            if (!res.getStatusCode().is2xxSuccessful()) {
                if (res.getStatusCode().value() == 401 || res.getStatusCode().value() == 429) {
                    stopped = "Stopped: The Odds API answered " + res.getStatusCode().value() + ".";
                    break;
                }
                errors++;   // e.g. the match has started and is no longer listed
                lastError = "HTTP " + res.getStatusCode().value() + ": " + Json.text(Json.parse(res.getBody()).get("message"));
                continue;
            }
            int cost = number(res.getHeaders().getFirst("x-requests-last"));
            java.util.Set<String> got = new java.util.TreeSet<>();
            store.transaction(() -> {
                got.addAll(savePrices(id, Json.maps(Json.parse(res.getBody()).get("bookmakers")), new java.util.HashSet<>(markets),
                        java.util.Set.of(bookmaker)));
                store.update("INSERT OR REPLACE INTO odds_extra_fetches (event_id, bookmaker, fetched_at_iso, markets, credits) "
                        + "VALUES (?, ?, ?, ?, ?)", id, bookmaker, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), String.join(",", got), cost);
            });
            got.forEach(m -> returned.merge(m, 1, Integer::sum));
            matches++;
            credits += cost;
        }
        out.put("sport", sport);
        out.put("bookmaker", bookmaker);
        out.put("matches", matches);
        out.put("credits", credits);
        out.put("markets", returned);
        out.put("errors", errors);
        out.put("lastError", lastError);
        out.put("stopped", stopped);
        return out;
    }

    /** Markets fetched in-play: the match result plus whichever of these the bookmaker offers. */
    static final List<String> LIVE_MARKETS = List.of("alternate_totals", "totals", "btts", "draw_no_bet");
    /** The points in a match that in-play prices are saved for (the match page's timeline). */
    static final List<String> LIVE_POINTS = List.of("30", "HT", "60", "80");

    /**
     * In-play prices for one match right now (The Odds API lists games while they're being played): the match
     * result plus the goals, BTTS and Draw No Bet markets this bookmaker offers. 1 credit per market returned.
     * Saved apart from the pre-match prices, under the point in the match they were fetched at (10, 25, HT, 60, 80),
     * so clicking that point on the timeline later shows them again without another call.
     * Returns {eventId, bookmaker, point, fetchedAt, credits, markets:[{key, outcomes:[{name, price, point}]}]}.
     */
    public Map<String, Object> refreshLive(String sport, String eventId, String bookmaker, String point) {
        return refreshLive(sport, eventId, bookmaker, point, null);
    }

    /**
     * As above, but exactly {@code only} (null: the usual match result + goals + BTTS): e.g. what's ticked in
     * Settings. Markets this bookmaker doesn't offer are left out ("h2h" is always offered).
     */
    public Map<String, Object> refreshLive(String sport, String eventId, String bookmaker, String point, List<String> only) {
        return fetchLive(sport, eventId, bookmaker, point, only, true);
    }

    /**
     * Test: the in-play prices for exactly these markets right now, without saving them under any point (so the bet
     * windows aren't touched). Charged like a bet window: 1 credit per market returned.
     */
    public Map<String, Object> testLive(String sport, String eventId, String bookmaker, List<String> only) {
        return fetchLive(sport, eventId, bookmaker, null, only, false);
    }

    private Map<String, Object> fetchLive(String sport, String eventId, String bookmaker, String point, List<String> only, boolean save) {
        OddsApiClient.safeId(sport);
        OddsApiClient.safeId(eventId);
        if (save && !LIVE_POINTS.contains(point)) {
            throw new IllegalArgumentException("Unknown point in the match: " + point);
        }
        List<String> offered = extraMarkets(bookmaker);
        List<String> markets = new ArrayList<>();
        if (only == null || only.isEmpty()) {
            markets.add("h2h");
            for (String m : offered) {
                if (LIVE_MARKETS.contains(m)) {
                    markets.add(m);
                }
            }
        } else {
            for (String m : only) {
                // h2h and totals (the main goal line) are the featured markets: always there, not in "offered"
                if (("h2h".equals(m) || "totals".equals(m) || offered.contains(m)) && !markets.contains(m)) {
                    markets.add(m);
                }
            }
        }
        if (creditsLeft == 0) {
            throw new IllegalStateException("No Odds API credits left this month.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", String.join(",", markets));
        params.put("bookmakers", bookmaker);
        params.put("oddsFormat", "decimal");
        params.put("dateFormat", "iso");
        ResponseEntity<String> res = client.get("/sports/" + sport + "/events/" + eventId + "/odds", params);
        remember(res);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("The Odds API answered " + res.getStatusCode().value() + ": "
                    + Json.text(Json.parse(res.getBody()).get("message")));
        }
        List<Map<String, Object>> got = new ArrayList<>();
        for (Map<String, Object> b : Json.maps(Json.parse(res.getBody()).get("bookmakers"))) {
            if (bookmaker.equals(b.get("key"))) {
                for (Map<String, Object> m : Json.maps(b.get("markets"))) {
                    List<Map<String, Object>> outcomes = new ArrayList<>();
                    for (Map<String, Object> o : Json.maps(m.get("outcomes"))) {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("name", o.get("name"));
                        x.put("price", o.get("price"));
                        if (o.get("point") != null) {
                            x.put("point", o.get("point"));
                        }
                        if (o.get("description") != null) {
                            x.put("description", o.get("description"));   // the player, in goalscorer markets ("Yes" is the name)
                        }
                        outcomes.add(x);
                    }
                    got.add(Map.of("key", String.valueOf(m.get("key")), "outcomes", outcomes));
                }
            }
        }
        if (got.isEmpty()) {   // e.g. after full time, or the bookmaker has suspended its markets: keep what was saved
            throw new IllegalStateException("The bookmaker has no in-play odds for this match right now (asked for "
                    + String.join(", ", markets) + "). Nothing was charged and the saved odds are kept.");
        }
        String fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        int cost = number(res.getHeaders().getFirst("x-requests-last"));
        if (save) store.update("INSERT OR REPLACE INTO odds_live_points (event_id, bookmaker, point, fetched_at_iso, credits, markets) "
                + "VALUES (?, ?, ?, ?, ?, ?)", eventId, bookmaker, point, fetchedAt, cost, Json.write(got));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", eventId);
        out.put("bookmaker", bookmaker);
        out.put("point", point);
        out.put("fetchedAt", fetchedAt);
        out.put("credits", cost);
        out.put("asked", markets);
        out.put("markets", got);
        return out;
    }

    /** Odds API credits left this month, as of the last call (-1 if unknown). */
    public long creditsLeft() {
        return creditsLeft;
    }

    /**
     * The saved odds event for a GOAL API game (its fixture id), through the game both are linked to.
     * Returns {sport, id}, or empty if it isn't in the saved odds.
     */
    public Optional<Map<String, String>> findEvent(String goalFixtureId) {
        return games.game(com.sportsbettingassistant.football.Games.GOAL, goalFixtureId)
                .flatMap(games::oddsEvent)
                .flatMap(id -> store.first("SELECT sport FROM odds_events WHERE id = ?", rs -> rs.getString(1), id)
                        .map(sport -> Map.of("sport", sport, "id", id)));
    }

    /**
     * The in-play prices saved for a match (no API call), by point in the match:
     * {eventId, bookmaker, points:{"HT":{fetchedAt, credits, markets}, ...}} (points is empty when there are none).
     */
    public Map<String, Object> live(String eventId, String bookmaker) {
        Map<String, Object> points = new LinkedHashMap<>();
        store.query("SELECT * FROM odds_live_points WHERE event_id = ? AND bookmaker = ?", rs -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("fetchedAt", rs.getString("fetched_at_iso"));
            p.put("credits", rs.getInt("credits"));
            p.put("markets", Json.parse("{\"m\":" + rs.getString("markets") + "}").get("m"));
            points.put(rs.getString("point"), p);
            return p;
        }, eventId, bookmaker);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", eventId);
        out.put("bookmaker", bookmaker);
        out.put("points", points);
        return out;
    }

    /**
     * Which markets each bookmaker in a region has for one match (The Odds API's event markets endpoint,
     * 1 credit). A diagnostic: shows e.g. whether Betfred offers BTTS where Sky Bet doesn't.
     * Returns {sport, eventId, home, away, credits, bookmakers:{key:[markets]}}.
     */
    public Map<String, Object> availableMarkets(String sport, String eventId, String regions) {
        OddsApiClient.safeId(sport);
        OddsApiClient.safeId(eventId);
        String region = checkList(regions, "regions");
        ResponseEntity<String> res = client.get("/sports/" + sport + "/events/" + eventId + "/markets", Map.of("regions", region));
        remember(res);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalArgumentException("The Odds API answered " + res.getStatusCode().value() + ": "
                    + Json.text(Json.parse(res.getBody()).get("message")));
        }
        Map<String, Object> body = Json.map(Json.parse(res.getBody()));
        Map<String, Object> books = new java.util.TreeMap<>();
        for (Map<String, Object> b : Json.maps(body.get("bookmakers"))) {
            books.put(Json.text(b.get("key")), Json.maps(b.get("markets")).stream().map(x -> Json.text(x.get("key"))).sorted().toList());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sport", sport);
        out.put("eventId", eventId);
        out.put("home", body.get("home_team"));
        out.put("away", body.get("away_team"));
        out.put("credits", number(res.getHeaders().getFirst("x-requests-last")));
        out.put("bookmakers", books);
        return out;
    }

    private void remember(ResponseEntity<String> res) {
        String left = res.getHeaders().getFirst("x-requests-remaining");
        if (left != null && !left.isBlank()) {
            creditsLeft = (long) Double.parseDouble(left.trim());
        }
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Saved odds for a sport, in the shape the pages use: {sport, fetchedAt, ..., events:[The Odds API events]}. Free. */
    public Optional<String> read(String sport) {
        OddsApiClient.safeId(sport);
        return store.first("SELECT * FROM odds_fetches WHERE sport = ?", rs -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sport", rs.getString("sport"));
            out.put("fetchedAt", rs.getString("fetched_at_iso"));
            out.put("days", Store.longOrNull(rs, "days"));
            out.put("markets", rs.getString("markets"));
            out.put("bookmakers", rs.getString("bookmakers"));
            out.put("creditsUsed", rs.getString("credits"));
            return out;
        }, sport).map(out -> {
            out.put("events", events(sport));
            return Json.write(out);
        });
    }

    /** Whether a sport's saved odds have any matches (Refresh Stats only fetches for those). */
    public boolean hasFixtures(String sport) {
        return eventCount(sport) > 0;
    }

    /** How many matches a sport's saved odds have. */
    public int eventCount(String sport) {
        return store.first("SELECT COUNT(*) FROM odds_events WHERE sport = ?", rs -> rs.getInt(1), sport).orElse(0);
    }

    /** Football tournaments (API sport keys) that have saved odds. */
    public List<String> cachedSports() {
        return store.query("SELECT sport FROM odds_fetches WHERE sport LIKE ? ORDER BY sport",
                rs -> rs.getString(1), FOOTBALL_API_PREFIX + "%");
    }

    private List<Map<String, Object>> events(String sport) {
        Map<String, Map<String, Object>> events = new LinkedHashMap<>();
        for (Map<String, Object> e : store.query("SELECT e.*, g.id AS game_id, g.home_team_id, g.away_team_id FROM odds_events e "
                + "LEFT JOIN game_sources s ON s.source = 'odds' AND s.source_id = e.id LEFT JOIN games g ON g.id = s.game_id "
                + "WHERE e.sport = ? ORDER BY e.kickoff, e.id", rs -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("id", rs.getString("id"));
            // our game and its teams (GOAL API's, as the stats tables have them): null if the names weren't matched
            ev.put("game_id", Store.longOrNull(rs, "game_id"));
            ev.put("home_team_id", Store.longOrNull(rs, "home_team_id"));
            ev.put("away_team_id", Store.longOrNull(rs, "away_team_id"));
            ev.put("sport_key", sport);
            ev.put("sport_title", rs.getString("sport_title"));
            ev.put("commence_time", rs.getString("kickoff"));
            ev.put("home_team", rs.getString("home_team"));
            ev.put("away_team", rs.getString("away_team"));
            ev.put("extras_fetched_at", new LinkedHashMap<String, String>());   // bookmaker -> when its extra markets were fetched
            ev.put("bookmakers", new ArrayList<Map<String, Object>>());
            return ev;
        }, sport)) {
            events.put(String.valueOf(e.get("id")), e);
        }
        store.query("SELECT x.event_id, x.bookmaker, x.fetched_at_iso FROM odds_extra_fetches x JOIN odds_events e ON e.id = x.event_id "
                + "WHERE e.sport = ?", rs -> {
                    Map<String, Object> ev = events.get(rs.getString(1));
                    if (ev != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> at = (Map<String, String>) ev.get("extras_fetched_at");
                        at.put(rs.getString(2), rs.getString(3));
                    }
                    return null;
                }, sport);
        store.query("SELECT p.* FROM odds_prices p JOIN odds_events e ON e.id = p.event_id WHERE e.sport = ? "
                + "ORDER BY p.event_id, CASE WHEN p.market IN ('h2h', 'totals', 'spreads') THEN 0 ELSE 1 END, p.position", rs -> {
                    Map<String, Object> ev = events.get(rs.getString("event_id"));
                    if (ev == null) {
                        return null;
                    }
                    Map<String, Object> bookmaker = find(list(ev, "bookmakers"), rs.getString("bookmaker"), () -> {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("key", rs.getString("bookmaker"));
                        b.put("title", rs.getString("bookmaker_title"));
                        b.put("markets", new ArrayList<Map<String, Object>>());
                        return b;
                    });
                    Map<String, Object> market = find(list(bookmaker, "markets"), rs.getString("market"), () -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("key", rs.getString("market"));
                        m.put("last_update", rs.getString("last_update"));
                        m.put("outcomes", new ArrayList<Map<String, Object>>());
                        return m;
                    });
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("name", rs.getString("outcome"));
                    String description = rs.getString("description");
                    if (description != null) {
                        o.put("description", description);
                    }
                    o.put("price", Store.doubleOrNull(rs, "price"));
                    Double point = Store.doubleOrNull(rs, "point");
                    if (point != null) {
                        o.put("point", point == Math.rint(point) ? (Object) point.longValue() : point);
                    }
                    list(market, "outcomes").add(o);
                    return null;
                }, sport);
        return new ArrayList<>(events.values());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    /** The entry with this key in a list, adding a new one (made by {@code create}) if missing. */
    private static Map<String, Object> find(List<Map<String, Object>> list, String key, SqlSupplier create)
            throws java.sql.SQLException {
        for (Map<String, Object> m : list) {
            if (java.util.Objects.equals(m.get("key"), key)) {
                return m;
            }
        }
        Map<String, Object> m = create.get();
        list.add(m);
        return m;
    }

    private interface SqlSupplier {
        Map<String, Object> get() throws java.sql.SQLException;
    }

    private static Double decimal(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? null : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String checkList(String value, String name) {
        String v = value == null ? "" : value.replaceAll("\\s", "");
        if (v.isEmpty() || !SAFE_LIST.matcher(v).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return v;
    }
}
