package com.sportsbettingassistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.sportsbettingassistant.settings.SettingsService;

@Controller
public class HomeController {

    private final SettingsService settings;

    public HomeController(SettingsService settings) {
        this.settings = settings;
    }

    /** The top menu does the navigating, so the home page goes straight to Football (Settings for a new user). */
    @GetMapping("/")
    public String index() {
        return settings.ready() ? "redirect:/football" : "redirect:/settings";
    }

    /** Bets: every recommended bet across the upcoming games. */
    @GetMapping("/bets")
    public String bets() {
        return "bets";
    }

    @GetMapping("/horse-racing")
    public String horseRacing() {
        return "horse-racing";
    }
}
