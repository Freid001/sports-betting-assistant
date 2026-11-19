package com.sportsbettingassistant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HomeControllerTest {

    @Test
    void homeGoesToFootballAndHorseRacingHasAPage() {
        HomeController home = new HomeController();
        assertThat(home.index()).isEqualTo("redirect:/football");
        assertThat(home.horseRacing()).isEqualTo("horse-racing");
    }
}
