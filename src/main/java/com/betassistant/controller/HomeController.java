package com.betassistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    /** The top menu does the navigating, so the home page goes straight to Football. */
    @GetMapping("/")
    public String index() {
        return "redirect:/football";
    }

    @GetMapping("/horse-racing")
    public String horseRacing() {
        return "horse-racing";
    }
}
