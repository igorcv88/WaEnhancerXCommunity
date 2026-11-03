package com.waenhancer.xposed.features.general;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded parser for single-row Android SQLite writes; never rewrites arbitrary SQL. */
public final class RevokeSql {
    private static final String ID = "[\\\"`\\[]?([a-z_][a-z_0-9]*)[\\\"`\\]]?";
    private static final Pattern DELETE = Pattern.compile("(?is)^DELETE\\s+FROM\\s+" + ID + "\\s+WHERE\\s+(.+?)\\s*;?$");
    private static final Pattern UPDATE = Pattern.compile("(?is)^UPDATE(?:\\s+OR\\s+\\w+)?\\s+" + ID + "\\s+SET\\s+(.+?)\\s+WHERE\\s+(.+?)\\s*;?$");
    private static final Pattern INSERT = Pattern.compile("(?is)^(?:INSERT(?:\\s+OR\\s+\\w+)?|REPLACE)\\s+INTO\\s+" + ID + "\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)\\s*;?$");
    private static final Pattern EQ = Pattern.compile("(?is)^" + ID + "\\s*=\\s*(\\?|[0-9]+)$");
    private static final Pattern SCOPED = Pattern.compile("(?is)^(DELETE\\s+FROM|UPDATE(?:\\s+OR\\s+\\w+)?|(?:INSERT(?:\\s+OR\\s+\\w+)?|REPLACE)\\s+INTO)\\s+"
            + ID + "(?:\\s|\\().*");
    public final String table;
    public final long row;
    public final boolean delete;
    public final boolean placeholder;
    public final String key;
    public final long chat;
    public final Map<String, Object> predicates;

    private RevokeSql(String table, long row, boolean delete, boolean placeholder, String key, long chat,
            Map<String, Object> predicates) {
        this.table = table; this.row = row; this.delete = delete; this.placeholder = placeholder;
        this.key = key; this.chat = chat;
        this.predicates = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(predicates));
    }

    public static RevokeSql parse(String sql, Object[] args) {
        if (sql == null || sql.length() > 32768) return null;
        sql = sql.trim();
        Matcher match = DELETE.matcher(sql);
        int[] at = {0};
        try {
            if (match.matches()) {
                String table = match.group(1).toLowerCase(Locale.ROOT);
                if (!target(table)) return null;
                Map<String, Object> where = equalsList(match.group(2), "(?i)\\s+AND\\s+", args, at);
                return mutation(table, where, true, false, where);
            }
            match = UPDATE.matcher(sql);
            if (match.matches()) {
                String table = match.group(1).toLowerCase(Locale.ROOT);
                if (!target(table)) return null;
                Map<String, Object> values = assignments(match.group(2), args, at);
                Map<String, Object> where = equalsList(match.group(3), "(?i)\\s+AND\\s+", args, at);
                return mutation(table, where, false, number(values.get("message_type")) == 15, where);
            }
            match = INSERT.matcher(sql);
            if (match.matches()) {
                String table = match.group(1).toLowerCase(Locale.ROOT);
                if (!target(table)) return null;
                String[] columns = match.group(2).split(",");
                String[] tokens = match.group(3).split(",", -1);
                if (columns.length != tokens.length) return null;
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.length; i++) values.put(unquote(columns[i]), value(tokens[i], args, at));
                return mutation(table, values, false, number(values.get("message_type")) == 15, Map.of());
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalidShape) {
            return null;
        }
        return null;
    }

    /** Wider barrier only inside an identified revoke callback, never a global bulk hook. */
    static RevokeSql scopedMutation(String sql, long row) {
        if (sql == null || sql.length() > 32768 || row <= 0) return null;
        Matcher write = SCOPED.matcher(sql.trim());
        if (!write.matches()) return null;
        String table = write.group(2).toLowerCase(Locale.ROOT);
        if (!target(table)) return null;
        String verb = write.group(1).toUpperCase(Locale.ROOT);
        if (verb.startsWith("UPDATE") && !sql.toLowerCase(Locale.ROOT).contains("message_type")) return null;
        return new RevokeSql(table, row, verb.startsWith("DELETE"), !verb.startsWith("DELETE"), null, -1, Map.of());
    }

    private static boolean target(String table) { return table.equals("message") || table.equals("message_media"); }
    private static RevokeSql mutation(String table, Map<String, Object> values, boolean delete, boolean placeholder, Map<String, Object> predicates) {
        long row = number(values.get(table.equals("message") ? "_id" : "message_row_id"));
        String key = values.get("key_id") instanceof String ? (String) values.get("key_id") : null;
        long chat = number(values.get("chat_row_id"));
        return row > 0 || (placeholder && key != null && chat > 0)
                ? new RevokeSql(table, row, delete, placeholder, key, chat, predicates) : null;
    }
    private static String unquote(String name) { return name.trim().replace("\"", "").replace("`", "").replace("[", "").replace("]", "").toLowerCase(Locale.ROOT); }
    private static Map<String, Object> equalsList(String input, String separator, Object[] args, int[] at) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String part : input.replaceAll(";\\s*$", "").split(separator)) {
            part = part.trim();
            if (part.startsWith("(") && part.endsWith(")")) part = part.substring(1, part.length() - 1).trim();
            Matcher equality = EQ.matcher(part);
            if (!equality.matches()) throw new IllegalArgumentException("Unsupported predicate");
            String name = equality.group(1).toLowerCase(Locale.ROOT);
            if (result.containsKey(name)) throw new IllegalArgumentException("Repeated predicate");
            result.put(name, value(equality.group(2), args, at));
        }
        return result;
    }
    private static Map<String, Object> assignments(String input, Object[] args, int[] at) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String part : input.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("Unsupported assignment");
            result.put(unquote(part.substring(0, eq)), value(part.substring(eq + 1), args, at));
        }
        return result;
    }
    private static Object value(String token, Object[] args, int[] at) {
        token = token.trim();
        if (token.equals("?")) {
            if (args == null || at[0] >= args.length) throw new IllegalArgumentException("Missing binding");
            return args[at[0]++];
        }
        if (token.equalsIgnoreCase("null")) return null;
        if (token.matches("[0-9]+")) return Long.parseLong(token);
        throw new IllegalArgumentException("Unsupported expression");
    }
    private static long number(Object value) {
        if (value == null) return -1;
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return -1; }
    }
}
