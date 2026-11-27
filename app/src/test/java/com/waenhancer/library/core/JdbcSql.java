package com.waenhancer.library.core;


import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real SQLite (xerial sqlite-jdbc) behind the library's {@link Sql} interface, so JVM tests run
 * the production SQL, constraints, transactions and migrations rather than a mock.
 * Transaction semantics mirror Android's: nested calls join the outer transaction and any
 * failure rolls the whole outer transaction back.
 */
public final class JdbcSql implements Sql, AutoCloseable {
    private final Connection connection;
    private int depth;
    private boolean failed;

    public JdbcSql(File file) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA busy_timeout = 10000");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized void exec(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) {
            statement.execute();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized long insert(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) {
            int changed = statement.executeUpdate();
            if (changed == 0) return -1;
            try (Statement last = connection.createStatement(); ResultSet rs = last.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) {
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized List<Map<String, Object>> query(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = prepare(sql, args); ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return rows;
    }

    @Override
    public synchronized <T> T inTransaction(Work<T> work) {
        try {
            if (depth == 0) {
                connection.setAutoCommit(false);
                failed = false;
            }
            depth++;
            try {
                T result = work.run();
                return result;
            } catch (RuntimeException failure) {
                failed = true;
                throw failure;
            } finally {
                depth--;
                if (depth == 0) {
                    if (failed) connection.rollback();
                    else connection.commit();
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized int userVersion() {
        return ((Number) query("PRAGMA user_version").get(0).values().iterator().next()).intValue();
    }

    @Override
    public synchronized void setUserVersion(int version) {
        exec("PRAGMA user_version = " + version);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private PreparedStatement prepare(String sql, Object[] args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }
}
