package com.waenhancer.xposed.features.general;

import java.util.ArrayDeque;
import java.util.concurrent.Callable;

/** Per-callback emergency retention survives a failed durable checkpoint. */
final class RevokeCallContext {
    static final class Call {
        final String scope;
        final long row;
        final String key;
        final boolean allow;
        Call(String scope, long row, String key, boolean allow) {
            this.scope = scope; this.row = row; this.key = key; this.allow = allow;
        }
    }

    private final ThreadLocal<ArrayDeque<Call>> frames = ThreadLocal.withInitial(ArrayDeque::new);

    void enter() { frames.get().push(new Call(null, 0, null, false)); }
    Call current() { return frames.get().peek(); }
    void leave() {
        ArrayDeque<Call> stack = frames.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) frames.remove();
    }

    void protect(String scope, long row, String key, boolean allow) {
        ArrayDeque<Call> stack = frames.get();
        if (stack.isEmpty()) throw new IllegalStateException("Revocation callback scope unavailable");
        if (scope == null || scope.isEmpty() || row <= 0 || key == null || key.isEmpty())
            throw new IllegalArgumentException("Revocation identity unavailable");
        stack.pop();
        stack.push(new Call(scope, row, key, allow));
    }

    long capture(String scope, long row, String key, Callable<Long> snapshot) throws Exception {
        // This must precede ALL snapshot work, including size checks, encryption and SQLite.
        // A failure keeps this identity armed until the native callback finishes.
        protect(scope, row, key, false);
        Long committed = snapshot.call();
        if (committed == null || committed <= 0)
            throw new IllegalStateException("Revocation snapshot not acknowledged");
        return committed;
    }

    boolean owns(String scope, long row, String key) {
        Call call = current();
        return call != null && !call.allow && scope.equals(call.scope)
                && row == call.row && key != null && key.equals(call.key);
    }

    RevokeSql fallback(String scope, String sql) {
        Call call = current();
        return call != null && !call.allow && scope.equals(call.scope)
                ? RevokeSql.scopedMutation(sql, call.row) : null;
    }
}
