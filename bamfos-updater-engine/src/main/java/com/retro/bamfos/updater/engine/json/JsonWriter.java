package com.retro.bamfos.updater.engine.json;

import java.util.List;
import java.util.Map;

/** Serializes plain Java shapes (Map/List/String/Long/Boolean/null) to JSON for ledger and journal writes. */
public final class JsonWriter {

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        val(b, v, 0);
        return b.toString();
    }

    private static void val(StringBuilder b, Object v, int depth) {
        switch (v) {
            case null -> b.append("null");
            case String s -> str(b, s);
            case Boolean x -> b.append(x);
            case Long l -> b.append(l);
            case Integer i -> b.append(i);
            case Double d -> b.append(d);
            case Map<?, ?> m -> {
                b.append("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        b.append(",");
                    }
                    first = false;
                    nl(b, depth + 1);
                    str(b, String.valueOf(e.getKey()));
                    b.append(": ");
                    val(b, e.getValue(), depth + 1);
                }
                if (!first) {
                    nl(b, depth);
                }
                b.append("}");
            }
            case List<?> l -> {
                b.append("[");
                boolean first = true;
                for (Object e : l) {
                    if (!first) {
                        b.append(",");
                    }
                    first = false;
                    nl(b, depth + 1);
                    val(b, e, depth + 1);
                }
                if (!first) {
                    nl(b, depth);
                }
                b.append("]");
            }
            default -> throw new IllegalArgumentException("unwritable type " + v.getClass());
        }
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
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
        b.append('"');
    }

    private static void nl(StringBuilder b, int depth) {
        b.append('\n').append("  ".repeat(depth));
    }

    private JsonWriter() {
    }
}
