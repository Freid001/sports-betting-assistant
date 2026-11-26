package com.sportsbettingassistant.settings;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A new user (no API keys yet) gets the Settings page: the Bets, Football and Horse Racing pages need both keys,
 * so opening one of them goes to Settings until both are saved. The pages' data endpoints (/api/...) are left alone.
 */
@Configuration
public class SetupGate implements WebMvcConfigurer, HandlerInterceptor {

    static final String[] PAGES = {"/bets", "/football", "/football/**", "/horse-racing"};

    private final SettingsService settings;

    public SetupGate(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(PAGES);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (settings.ready()) {
            return true;
        }
        response.sendRedirect("/settings");
        return false;
    }
}
