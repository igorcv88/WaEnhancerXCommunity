package com.waenhancer.library.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.waenhancer.library.core.Sql;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link Sql} over the platform SQLite. WAL lets the gallery read while the provider imports. */
public final class AndroidSql implements Sql {
    private final SQLiteDatabase db;

    private AndroidSql(SQLiteDatabase db) {
        this.db = db;
    }

    public static AndroidSql open(File file) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        db.setForeignKeyConstraintsEnabled(true);
        return new AndroidSql(db);
    }

    @Override
    public void exec(String sql, Object... args) {
        if (args.length == 0) db.execSQL(sql);
        else db.execSQL(sql, args);
    }

    @Override
    public long insert(String sql, Object... args) {
        try (SQLiteStatement statement = compile(sql, args)) {
            return statement.executeInsert();
        }
    }

    @Override
    public int update(String sql, Object... args) {
        try (SQLiteStatement statement = compile(sql, args)) {
            return statement.executeUpdateDelete();
        }
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... args) {
        String[] bound = new String[args.length];
        for (int i = 0; i < args.length; i++) bound[i] = args[i] == null ? null : String.valueOf(args[i]);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, bound)) {
            String[] names = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < names.length; i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.put(names[i], null); break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(names[i], cursor.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(names[i], cursor.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_BLOB: row.put(names[i], cursor.getBlob(i)); break;
                        default: row.put(names[i], cursor.getString(i)); break;
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public <T> T inTransaction(Work<T> work) {
        db.beginTransaction();
        try {
            T result = work.run();
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public int userVersion() {
        return db.getVersion();
    }

    @Override
    public void setUserVersion(int version) {
        db.setVersion(version);
    }

    private SQLiteStatement compile(String sql, Object[] args) {
        SQLiteStatement statement = db.compileStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int index = i + 1;
            if (value == null) statement.bindNull(index);
            else if (value instanceof Long || value instanceof Integer) statement.bindLong(index, ((Number) value).longValue());
            else if (value instanceof Boolean) statement.bindLong(index, (Boolean) value ? 1 : 0);
            else if (value instanceof byte[]) statement.bindBlob(index, (byte[]) value);
            else statement.bindString(index, value.toString());
        }
        return statement;
    }
}
