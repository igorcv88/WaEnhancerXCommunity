package com.waenhancer.library.core;

import java.util.List;
import java.util.Map;

/**
 * The minimal SQL surface the media library needs.
 *
 * <p>The library catalog is written against this interface instead of
 * {@code android.database.sqlite} so that the exact same SQL, constraints, transactions and
 * migrations run on the device (through {@code AndroidSql}) and in JVM tests (through a JDBC
 * SQLite adapter). Arguments are bound, never concatenated.</p>
 */
public interface Sql {

    /** Executes a statement that returns no rows. */
    void exec(String sql, Object... args);

    /** Executes an INSERT and returns the new row id, or -1 when nothing was inserted. */
    long insert(String sql, Object... args);

    /** Executes an UPDATE/DELETE and returns the number of changed rows. */
    int update(String sql, Object... args);

    /** Executes a query and returns every row. Callers keep result sets small with LIMIT. */
    List<Map<String, Object>> query(String sql, Object... args);

    /** Runs {@code work} in a transaction. Nested calls join the outer transaction. */
    <T> T inTransaction(Work<T> work);

    /** Reads {@code PRAGMA user_version}. */
    int userVersion();

    /** Writes {@code PRAGMA user_version}; must be called inside a transaction. */
    void setUserVersion(int version);

    @FunctionalInterface
    interface Work<T> {
        T run();
    }

    /** Typed accessors for the generic rows returned by {@link #query}. */
    final class Rows {
        private Rows() { }

        public static long getLong(Map<String, Object> row, String column) {
            Object value = row.get(column);
            if (value == null) return 0L;
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }

        public static String getString(Map<String, Object> row, String column) {
            Object value = row.get(column);
            return value == null ? null : value.toString();
        }
    }
}
