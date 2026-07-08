package com.retro.bamfmenu.early.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* A tiny, dependency-free JSON reader for the descriptor files (song.json now; panel.track / panel.json
   later). The service jar runs pre-Minecraft and ships no Gson, so per the self-contained-jar rule this is
   hand-rolled: a small recursive-descent parser covering exactly what descriptors need -- objects, arrays,
   strings (with the standard escapes + \\uXXXX), numbers (int/double), true/false/null -- plus an Obj view
   with typed, DEFAULTING getters so callers stay forward-compatible: unknown fields are ignored and a missing
   field falls back to the supplied default. Read-only for now; the editor adds writing. No GL/MC. */
public final class Json {

    private Json() {
    }

    /* Parse a document into Map<String,Object> | List<Object> | String | Double | Boolean | null. Throws
       JsonException on malformed input (callers degrade to defaults). */
    public static Object parse(String src) {
        Parser p = new Parser(src);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.end()) {
            throw new JsonException("trailing content at " + p.pos);
        }
        return v;
    }

    /* Parse and view as an object; a non-object (or parse failure) yields an EMPTY Obj so callers can read
       with defaults without null checks. */
    public static Obj object(String src) {
        try {
            Object v = parse(src);
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                return new Obj(mm);
            }
        } catch (RuntimeException ignored) {
            // fall through to empty
        }
        return new Obj(new LinkedHashMap<>());
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) {
            super(msg);
        }
    }

    /* A read-only, defaulting view over a JSON object. Every getter returns the default when the key is
       absent OR holds the wrong type, so a malformed/partial descriptor never throws here. */
    public static final class Obj {
        private final Map<String, Object> m;

        Obj(Map<String, Object> m) {
            this.m = m;
        }

        public boolean has(String key) {
            return m.get(key) != null;
        }

        public String str(String key, String def) {
            return m.get(key) instanceof String s ? s : def;
        }

        public double dbl(String key, double def) {
            return m.get(key) instanceof Number n ? n.doubleValue() : def;
        }

        public float f(String key, float def) {
            return m.get(key) instanceof Number n ? n.floatValue() : def;
        }

        public int i(String key, int def) {
            return m.get(key) instanceof Number n ? n.intValue() : def;
        }

        public boolean bool(String key, boolean def) {
            return m.get(key) instanceof Boolean b ? b : def;
        }

        /* A nested object as an Obj, or an empty Obj if absent/wrong-type (so chained reads never NPE). */
        public Obj obj(String key) {
            if (m.get(key) instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) mm;
                return new Obj(cast);
            }
            return new Obj(new LinkedHashMap<>());
        }

        /* A nested array of OBJECTS as a List<Obj> (absent/wrong-type or non-object elements skipped), for arrays
           like song.json's "difficulties". Never null. */
        public List<Obj> objs(String key) {
            List<Obj> out = new ArrayList<>();
            if (m.get(key) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> mm) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) mm;
                        out.add(new Obj(cast));
                    }
                }
            }
            return out;
        }

        /* The set of keys (insertion order), e.g. to iterate the "charts" map's difficulty names. */
        public Iterable<String> keys() {
            return m.keySet();
        }
    }

    /* ---- the recursive-descent parser ---- */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean end() {
            return pos >= s.length();
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (end()) {
                throw new JsonException("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                case 'f':
                    return bool();
                case 'n':
                    lit("null");
                    return null;
                default:
                    return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++; // {
            ws();
            if (peek('}')) {
                pos++;
                return out;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                out.put(key, value());
                ws();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++; // [
            ws();
            if (peek(']')) {
                pos++;
                return out;
            }
            while (true) {
                ws();
                out.add(value());
                ws();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (end()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (end()) {
                        throw new JsonException("bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new JsonException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("expected value at " + start);
            }
            return Double.parseDouble(num); // all JSON numbers are Double; Obj.i()/f() narrow as needed
        }

        Boolean bool() {
            if (peek('t')) {
                lit("true");
                return Boolean.TRUE;
            }
            lit("false");
            return Boolean.FALSE;
        }

        void lit(String word) {
            if (pos + word.length() > s.length() || !s.regionMatches(pos, word, 0, word.length())) {
                throw new JsonException("expected '" + word + "' at " + pos);
            }
            pos += word.length();
        }

        boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw new JsonException("expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }
}
