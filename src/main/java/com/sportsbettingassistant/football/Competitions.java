package com.sportsbettingassistant.football;

import java.util.Map;

/** Our tournament names -> football-data.org competition codes (free plan). One list for the whole app. */
public final class Competitions {

    public static final Map<String, String> CODES = Map.of(
            "epl", "PL",
            "uefa_champs_league", "CL",
            "fifa_world_cup", "WC",
            "uefa_european_championship", "EC");

    private Competitions() {
    }
}
