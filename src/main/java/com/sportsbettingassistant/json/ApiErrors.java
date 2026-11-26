package com.sportsbettingassistant.json;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Bad input anywhere in the app (e.g. an odd competition code or match id) answers 400 with a message; outside API errors 503. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return Json.message(400, String.valueOf(e.getMessage()));
    }

    /** An outside API refused or is down (e.g. "The Odds API answered 422: ..."): 503 with its message. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> unavailable(IllegalStateException e) {
        return Json.message(503, String.valueOf(e.getMessage()));
    }
}
