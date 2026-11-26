package com.sportsbettingassistant.settings;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Every page gets the chosen theme (the page head turns it into its colours) and whether the Stake Bar is shown. */
@ControllerAdvice
public class ThemeAdvice {

    private final SettingsService settings;

    public ThemeAdvice(SettingsService settings) {
        this.settings = settings;
    }

    /** {key, color, hover, rgb, text, textRgb, ink} (a map, which the page templates read directly). */
    /** Both API keys saved: the menu shows Bets, Football and Horse Racing (a new user only gets Settings). */
    @ModelAttribute("ready")
    public boolean ready() {
        return settings.ready();
    }

    /** Stake Bar on or off: the page head hides the top bar's stake buttons when off. */
    @ModelAttribute("stakeBar")
    public boolean stakeBar() {
        return settings.stakeBar();
    }

    /** The match page's alerts: {animations, sounds} (on unless switched off in Settings). */
    @ModelAttribute("matchAlerts")
    public java.util.Map<String, Object> matchAlerts() {
        return java.util.Map.of("animations", settings.animations(), "sounds", settings.sounds());
    }

    @ModelAttribute("theme")
    public java.util.Map<String, Object> theme() {
        return settings.theme().toMap();
    }
}
