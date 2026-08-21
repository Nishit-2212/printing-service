package com.jagdushah.printly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer.
 *
 * Only what this service needs: objects, arrays, strings, numbers, booleans, null. The reader
 * additionally tolerates {@code //} and slash-star comments so config.json can carry notes.
 */
public final class Json {

    private Json() {
    }

    /** Thrown for malformed input; the HTTP layer turns this into a 400. */
    public static final class SyntaxException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            writeNumber(n, sb);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(o, sb);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            writeValue(List.of(arr), sb);
        } else {
            writeString(String.valueOf(v), sb);
        }
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            sb.append(n.longValue());
            return;
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object v = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new SyntaxException("trailing content at offset " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new SyntaxException("expected a JSON object at the top level");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            // tolerate a UTF-8 BOM at the head of a hand-edited config file
            this.src = src.startsWith("\uFEFF") ? src.substring(1) : src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                    int end = src.indexOf("*/", pos + 2);
                    pos = end < 0 ? src.length() : end + 2;
                } else {
                    return;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new SyntaxException("unexpected end of input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new SyntaxException("expected a quoted key at offset " + pos);
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new SyntaxException("expected ',' or '}' at offset " + pos);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new SyntaxException("expected ',' or ']' at offset " + pos);
                }
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new SyntaxException("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new SyntaxException("unterminated escape");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new SyntaxException("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new SyntaxException("invalid escape \\" + esc);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            boolean fractional = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    pos++;
                } else {
                    break;
                }
            }
            String raw = src.substring(start, pos);
            if (raw.isEmpty()) {
                throw new SyntaxException("expected a value at offset " + start);
            }
            try {
                return fractional ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new SyntaxException("bad number '" + raw + "'");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new SyntaxException("unexpected token at offset " + pos);
            }
            pos += literal.length();
            return value;
        }

        private char peek() {
            if (atEnd()) {
                throw new SyntaxException("unexpected end of input");
            }
            return src.charAt(pos);
        }

        private void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new SyntaxException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------ accessors

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Like {@link #num} but keeps the fraction — paper geometry is rarely a whole number. */
    public static double dbl(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
    }
}
