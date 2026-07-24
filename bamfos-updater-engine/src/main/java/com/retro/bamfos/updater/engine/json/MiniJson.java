package com.retro.bamfos.updater.engine.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON parser with no runtime dependencies so the shim can load the engine standalone.
 * Parses to plain Map, List, String, Long, Double, Boolean, or null.
 */
public final class MiniJson {

    /** Thrown with the character offset so a bad manifest error points at the actual spot. */
    public static final class JsonError extends RuntimeException {
        public JsonError(String message, int at) {
            super(message + " (at offset " + at + ")");
        }
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.value();
        p.skipWs();
        if (p.pos != p.len) {
            throw new JsonError("trailing content after JSON value", p.pos);
        }
        return v;
    }

    private final String s;
    private final int len;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
        this.len = s.length();
    }

    private Object value() {
        skipWs();
        if (pos >= len) {
            throw new JsonError("unexpected end of input", pos);
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        // LinkedHashMap so error messages and re-serialization keep the author's field order
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new JsonError("expected object key string", pos);
            }
            String k = string();
            skipWs();
            if (peek() != ':') {
                throw new JsonError("expected ':' after key \"" + k + "\"", pos);
            }
            pos++;
            m.put(k, value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return m;
            }
            throw new JsonError("expected ',' or '}' in object", pos);
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return a;
        }
        while (true) {
            a.add(value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return a;
            }
            throw new JsonError("expected ',' or ']' in array", pos);
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= len) {
                throw new JsonError("unterminated string", pos);
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                if (pos >= len) {
                    throw new JsonError("unterminated escape", pos);
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (pos + 4 > len) {
                            throw new JsonError("truncated \\u escape", pos);
                        }
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonError("bad escape '\\" + e + "'", pos - 1);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < len && "0123456789+-.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        String tok = s.substring(start, pos);
        try {
            // integers stay Long so file sizes and abi levels compare exactly, never through a double
            if (tok.indexOf('.') < 0 && tok.indexOf('e') < 0 && tok.indexOf('E') < 0) {
                return Long.parseLong(tok);
            }
            return Double.parseDouble(tok);
        } catch (NumberFormatException e) {
            throw new JsonError("bad number '" + tok + "'", start);
        }
    }

    private Object literal(String word, Object val) {
        if (!s.startsWith(word, pos)) {
            throw new JsonError("unexpected token", pos);
        }
        pos += word.length();
        return val;
    }

    private char peek() {
        if (pos >= len) {
            throw new JsonError("unexpected end of input", pos);
        }
        return s.charAt(pos);
    }

    private void skipWs() {
        while (pos < len) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }
}
