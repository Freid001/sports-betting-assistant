package com.sportsbettingassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SportsBettingAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(SportsBettingAssistantApplication.class, args);
    }
}
