package com.sportsbettingassistant.football;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sportsbettingassistant.json.Json;

/**
 * Team badges from GOAL API. One call per competition returns every team with its badge and country; they are
 * saved in the teams tables (FootballTables) and reused, so badges normally cost no calls.
 */
@Service
public class TeamLogoService {

    private static final Logger log = LoggerFactory.getLogger(TeamLogoService.class);

    private final GoalStats goal;
    private final FootballTables tables;
    private final Set<String> failedToday = ConcurrentHashMap.newKeySet();   // tried once today: not on every page load

    public TeamLogoService(GoalStats goal, FootballTables tables) {
        this.goal = goal;
        this.tables = tables;
    }

    public boolean hasKey() {
        return goal.configured();
    }

    /**
     * Teams JSON ({"teams":[{"id","name","shortName","tla","crest","area"}...]}) for a tournament. Uses the saved
     * list unless {@code refresh} is true. Empty if no key is set and nothing is saved. A tournament we have no
     * competition for gets every saved team, marked "all" so pages match names strictly.
     */
    public Optional<String> teams(String tournament, boolean refresh) {
        String code = Competitions.CODES.get(tournament);
        if (code == null) {
            List<Map<String, Object>> teams = tables.allTeams();
            return teams.isEmpty() ? Optional.empty() : Optional.of(Json.write(Map.of("teams", teams, "all", true)));
        }
        Optional<String> saved = tables.teamsJson(code);
        String today = code + "|" + LocalDate.now();
        if ((!refresh && saved.isPresent()) || !hasKey() || (!refresh && failedToday.contains(today))) {
            return saved;
        }
        try {
            goal.saveTeams(code);
        } catch (RuntimeException e) {
            failedToday.add(today);
            log.warn("GOAL API teams for {} failed: {}", code, e.getMessage());
            return saved;
        }
        return tables.teamsJson(code);
    }
}
