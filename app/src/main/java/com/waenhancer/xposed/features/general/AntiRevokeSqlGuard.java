package com.waenhancer.xposed.features.general;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import com.waenhancer.xposed.core.db.RevocationArchive;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** One interception point for SQLiteDatabase APIs, execSQL and bound statements. */
final class AntiRevokeSqlGuard {
    private final RevocationArchive archive;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();
    private final RevokeCallContext calls = new RevokeCallContext();
    void enterCall() { calls.enter(); }
    void leaveCall() { calls.leave(); }
    void protectCall(SQLiteDatabase db, long row, String key, boolean allow) {
        calls.protect(RevocationArchive.scope(db), row, key, allow);
    }
    long captureForCall(SQLiteDatabase db, long row, String key) throws Exception {
        return calls.capture(RevocationArchive.scope(db), row, key,
                () -> archive.capture(db, row, key));
    }

    private Field database, sql, bindings;
    private volatile boolean installed;
    boolean isInstalled() { return installed; }

    AntiRevokeSqlGuard(RevocationArchive archive, SharedPreferences prefs, Runnable changed) {
        this.archive = archive; this.prefs = prefs; this.changed = changed;
    }

    boolean install() {
        java.util.Set<XC_MethodHook.Unhook> registered = new java.util.HashSet<>();
        try {
            database = field("mDatabase");
            sql = field("mSql");
            bindings = field("mBindArgs");
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    guard(param);
                }
            };
            for (String name : new String[]{"execute", "executeUpdateDelete", "executeInsert"}) {
                var hooks = XposedBridge.hookAllMethods(SQLiteStatement.class, name, hook);
                if (hooks.isEmpty()) throw new IllegalStateException("SQLite statement execution unavailable");
                registered.addAll(hooks);
            }
            installed = true;
            return true;
        } catch (Throwable failure) {
            for (var hook : registered) hook.unhook();
            warn("sql-install", failure);
            return false;
        }
    }

    private static Field field(String name) throws Exception {
        Field result = SQLiteProgram.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private void guard(XC_MethodHook.MethodHookParam param) {
        try {
            String query = (String) sql.get(param.thisObject);
            if (query == null || !query.toLowerCase(java.util.Locale.ROOT).contains("message")) return;
            SQLiteDatabase db = (SQLiteDatabase) database.get(param.thisObject);
            if (db == null || !new File(db.getPath()).getName().equals("msgstore.db")) return;
            RevokeSql mutation = RevokeSql.parse(query, (Object[]) bindings.get(param.thisObject));
            boolean scopedFallback = mutation == null;
            String scope = RevocationArchive.scope(db);
            RevokeCallContext.Call call = calls.current();
            if (call != null && scope.equals(call.scope) && call.allow) return;
            if (mutation == null) mutation = calls.fallback(scope, query);
            if (mutation == null) {
                // Do not log SQL, bind args, paths or exception messages.
                if (RevokeSql.scopedMutation(query, 1) != null) warn("unsupported-sql-shape", null);
                return;
            }
            long original = -1;
            if (mutation.placeholder) {
                // The armed callback remains authoritative even if its archive is unavailable,
                // or the placeholder has a different row/key and omits chat metadata.
                if (call != null && !call.allow && scope.equals(call.scope)) original = call.row;
                else if (mutation.key != null && mutation.chat > 0)
                    original = archive.recordedRow(scope, mutation.key, mutation.chat);
            }
            long targetRow = -1;
            String key = null;
            // Prefer the actual row, then a committed stable identity (replay with a new _id).
            for (long candidate : new long[]{mutation.row, original}) {
                if (candidate <= 0) continue;
                StringBuilder selection = new StringBuilder("SELECT key_id,from_me,message_type FROM message WHERE _id=?");
                java.util.List<String> arguments = new java.util.ArrayList<>();
                arguments.add(Long.toString(candidate));
                if (mutation.table.equals("message")) {
                    for (var predicate : mutation.predicates.entrySet()) {
                        // Identifier came from RevokeSql's strict identifier regex; values stay bound.
                        selection.append(" AND \"").append(predicate.getKey()).append("\"=?");
                        arguments.add(predicate.getValue() == null ? null : predicate.getValue().toString());
                    }
                }
                try (Cursor row = db.rawQuery(selection.toString(), arguments.toArray(new String[0]))) {
                    if (!row.moveToFirst() || row.getInt(1) != 0 || row.getInt(2) == 15) continue;
                    String actualKey = row.getString(0);
                    boolean identified = calls.owns(scope, candidate, actualKey);
                    if (actualKey == null || actualKey.isEmpty()
                            || !mutation.acceptsOriginalKey(candidate, actualKey, identified)
                            || (scopedFallback && call != null && !actualKey.equals(call.key))) continue;
                    targetRow = candidate;
                    key = actualKey;
                    break;
                }
            }
            if (targetRow <= 0) return;
            boolean scopedTarget = calls.owns(scope, targetRow, key);
            // A message write and an identified callback must not depend on another archive
            // lookup succeeding after the checkpoint has failed (for example, disk failure).
            boolean protectedAlready = mutation.table.equals("message_media") && !scopedTarget
                    && archive.timestamp(scope, targetRow, key) > 0;
            boolean enabled = scopedTarget || enabledForRow(db, targetRow);
            // Ordinary content updates are allowed. Match the existing single-row incoming
            // DELETE protection; media cleanup requires a committed event or identified callback.
            boolean block = mutation.table.equals("message_media")
                    ? mutation.delete && (protectedAlready || scopedTarget) && enabled
                    : enabled && (mutation.delete || mutation.placeholder);
            if (!block) return;
            try {
                archive.capture(db, targetRow, key);
            } catch (Exception failure) {
                // Emergency retention: leave the native row intact, but explicitly report
                // the missing durable acknowledgement. Never claim the event was committed.
                warn("snapshot-not-committed", failure);
            }
            Class<?> result = ((java.lang.reflect.Method) param.method).getReturnType();
            if (result == void.class) param.setResult(null);
            else if (result == int.class) param.setResult(0);
            else if (result == long.class) param.setResult(-1L);
            changed.run();
        } catch (Throwable failure) {
            warn("sql-guard-failed", failure);
        }
    }

    private boolean enabledForRow(SQLiteDatabase db, long row) {
        boolean status = false;
        try (Cursor chat = db.rawQuery("SELECT j.raw_string FROM message m JOIN chat c ON m.chat_row_id=c._id "
                + "JOIN jid j ON c.jid_row_id=j._id WHERE m._id=?", new String[]{Long.toString(row)})) {
            status = chat.moveToFirst() && "status@broadcast".equals(chat.getString(0));
        } catch (android.database.sqlite.SQLiteException optionalSchema) {
            // A missing JID must not disable message protection.
        }
        try { return Integer.parseInt(prefs.getString(status ? "antirevokestatus" : "antirevoke", "0")) != 0; }
        catch (RuntimeException invalidPreference) { return false; }
    }

    void warn(String reason, Throwable failure) {
        if (warnings.add(reason)) XposedBridge.log("[WAEX] AntiRevoke " + reason
                + (failure == null ? "" : " type=" + failure.getClass().getSimpleName()));
    }
}
