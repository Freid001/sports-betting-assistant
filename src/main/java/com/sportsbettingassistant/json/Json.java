package com.sportsbettingassistant.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.json.BasicJsonParser;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Small JSON helpers used across the app (no Jackson needed):
 * write maps/lists as JSON, read JSON safely, pick values out of parsed JSON, and send JSON responses.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** Maps, lists, sets, strings, numbers, booleans and null as JSON text. */
    public static String write(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object item : c) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(write(item));
            }
            return b.append(']').toString();
        }
        if (o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return quote(String.valueOf(o));
    }

    /** A JSON string literal, with quotes, backslashes and control characters escaped. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    // ---------------------------------------------------------------- reading

    /** Parses a JSON object; damaged or missing JSON gives an empty map instead of an error. */
    public static Map<String, Object> parse(String json) {
        try {
            return json == null ? new LinkedHashMap<>() : new BasicJsonParser().parseMap(json);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The value as a map, or an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** The maps in a list (anything else in it is skipped); empty if it isn't a list. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    /** Text, or null for null and the parser's "null". */
    public static String text(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? null : String.valueOf(o);
    }

    /** A whole number, or -1 if it isn't one. */
    public static long number(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** true, or the text "true" (the parser keeps booleans as text when reading saved files). */
    public static boolean yes(Object o) {
        return o != null && "true".equals(String.valueOf(o));
    }

    // ---------------------------------------------------------------- responses

    public static ResponseEntity<String> ok(String json) {
        return response(HttpStatusCode.valueOf(200), json);
    }

    public static ResponseEntity<String> ok(Object value) {
        return ok(write(value));
    }

    public static ResponseEntity<String> response(HttpStatusCode status, String json) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
    }

    /** {"message": "..."} with the given status. */
    public static ResponseEntity<String> message(int status, String message) {
        return response(HttpStatusCode.valueOf(status), "{\"message\":" + quote(message) + "}");
    }
}
