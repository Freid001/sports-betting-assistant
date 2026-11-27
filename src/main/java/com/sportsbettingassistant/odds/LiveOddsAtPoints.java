package com.sportsbettingassistant.odds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.sportsbettingassistant.football.live.LiveService;

/**
 * When Live Stats saves a timeline point of a followed game (30', HT, 60', 80'), fetch the picked bookmaker's in-play
 * prices once and save them under that point, so the match page shows that point's odds and verdicts later at no cost.
 * About 3 Odds API credits a point (15 a game).
 */
@Component
public class LiveOddsAtPoints {

    private static final Logger log = LoggerFactory.getLogger(LiveOddsAtPoints.class);

    private final OddsCacheService odds;
    private com.sportsbettingassistant.settings.SettingsService settings;   // Refresh Odds Live: On (at each bet window) or Off

    public LiveOddsAtPoints(OddsCacheService odds) {
        this.odds = odds;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettings(com.sportsbettingassistant.settings.SettingsService settings) {
        this.settings = settings;
    }

    @EventListener
    public void onPoint(LiveService.PointSaved p) {
        if (settings != null && !settings.liveOdds()) {
            return;   // switched off in Settings: no credits spent
        }
        Thread.startVirtualThread(() -> fetch(p));   // not inside the Live Stats refresh (it holds its lock)
    }

    /**
     * What to fetch at a point: exactly what's ticked in Settings' Odds card: the match result (h2h) if Home / Draw /
     * Away is ticked, plus every ticked extra market. Null (the usual match result, goals and BTTS) without Settings.
     */
    java.util.List<String> marketsFor(LiveService.PointSaved p) {
        if (settings == null) {
            return null;
        }
        java.util.Map<String, Object> opts = settings.refreshOptions();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!Boolean.FALSE.equals(opts.get("matchResult"))) {
            out.add("h2h");
        }
        if (opts.get("markets") instanceof java.util.List<?> ticked) {
            ticked.forEach(m -> out.add(String.valueOf(m)));
        }
        return out;
    }

    /** Fetches and saves the prices for one point; returns true if it did (nothing ticked: nothing fetched). */
    boolean fetch(LiveService.PointSaved p) {
        java.util.List<String> markets = marketsFor(p);
        if (markets != null && markets.isEmpty()) {
            log.info("Live odds at {} skipped: nothing ticked in Settings.", p.point());
            return false;
        }
        var event = odds.findEvent(p.kickoff(), p.home(), p.away());
        if (event.isEmpty()) {
            log.info("Live odds at {} skipped: {} v {} isn't in the saved odds.", p.point(), p.home(), p.away());
            return false;
        }
        try {
            odds.refreshLive(event.get().get("sport"), event.get().get("id"), odds.bookmaker(), p.point(), marketsFor(p));
            return true;
        } catch (RuntimeException e) {
            log.warn("Live odds at {} for {} v {} failed: {}", p.point(), p.home(), p.away(), e.getMessage());
            return false;
        }
    }
}
