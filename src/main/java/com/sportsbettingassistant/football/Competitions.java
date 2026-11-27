package com.sportsbettingassistant.football;

import java.util.Map;
import java.util.Set;

/** Our competitions: tournament names, the codes the tables use, and their GOAL API league ids. One list for the whole app. */
public final class Competitions {

    /** Our tournament names (The Odds API keys without "soccer_") -> competition codes used in the tables. */
    public static final Map<String, String> CODES = Map.of(
            "epl", "PL",
            "england_league1", "EL1",
            "uefa_champs_league", "CL",
            "uefa_europa_league", "EL",
            "uefa_europa_conference_league", "ECL",
            "uefa_nations_league", "UNL",
            "fifa_world_cup", "WC",
            "uefa_european_championship", "EC");

    /** Competition code -> GOAL API league id (from GET /leagues). Domestic leagues are here for form and tables. */
    public static final Map<String, String> GOAL_LEAGUES = Map.ofEntries(
            Map.entry("PL", "cmr77dvkr005nrx06lp7rvp49"),
            Map.entry("ELC", "cmr77dvkr005hrx068xaahpuh"),
            Map.entry("EL1", "cmr77dvkr005lrx061tywzotk"),
            Map.entry("BL1", "cmr77dvgm0002rx06rt2uqxii"),
            Map.entry("PD", "cmr77dvnt006nrx063v3w622e"),
            Map.entry("SA", "cmr77dvpd006yrx06zig7907g"),
            Map.entry("FL1", "cmr77dvqg007crx06q1kaceyo"),
            Map.entry("DED", "cmr77dvrh007vrx0664phtxs5"),
            Map.entry("PPL", "cmr77dvun00adrx06xz20yfxe"),
            Map.entry("BSA", "cmr77dvww00bfrx061thkr8z4"),
            Map.entry("CL", "cmr77dw3900f5rx06j05wgzv4"),
            Map.entry("EL", "cmr77dw3900f6rx06tuqwft2d"),
            Map.entry("ECL", "cmr77dw3900f9rx06laad8onf"),
            Map.entry("UNL", "cmr77dw4800fgrx06rwmig2h8"),
            Map.entry("EC", "cmr77dw4800ferx06u57oohrh"),
            Map.entry("WC", "cmr77dw4s00fmrx069yvhf8ce"));

    /** Display names saved with the tables (GOAL API's own names are the same or close). */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("PL", "Premier League"), Map.entry("ELC", "Championship"), Map.entry("EL1", "League One"),
            Map.entry("BL1", "Bundesliga"), Map.entry("PD", "La Liga"), Map.entry("SA", "Serie A"),
            Map.entry("FL1", "Ligue 1"), Map.entry("DED", "Eredivisie"), Map.entry("PPL", "Primeira Liga"),
            Map.entry("BSA", "Brasileirão"), Map.entry("CL", "UEFA Champions League"), Map.entry("EL", "UEFA Europa League"),
            Map.entry("ECL", "UEFA Conference League"), Map.entry("UNL", "UEFA Nations League"),
            Map.entry("EC", "UEFA Euro"), Map.entry("WC", "FIFA World Cup"));

    /** Cup competitions (the rest are leagues): the match page shows a team's domestic league position for these. */
    public static final Set<String> CUPS = Set.of("CL", "EL", "ECL", "UNL", "EC", "WC");

    /** A team's country -> its domestic league, so Champions League clubs get their league form and table too. */
    public static final Map<String, String> COUNTRY_LEAGUES = Map.of(
            "England", "PL", "Germany", "BL1", "Spain", "PD", "Italy", "SA",
            "France", "FL1", "Netherlands", "DED", "Portugal", "PPL", "Brazil", "BSA");

    /** Our tournament name for a competition code ("PL" -> "epl"), or null. */
    public static String tournament(String code) {
        return CODES.entrySet().stream().filter(e -> e.getValue().equals(code)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public static String type(String code) {
        return CUPS.contains(code) ? "CUP" : "LEAGUE";
    }

    private Competitions() {
    }
}
