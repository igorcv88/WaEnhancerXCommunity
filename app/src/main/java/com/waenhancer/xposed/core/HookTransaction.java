package com.waenhancer.xposed.core;

import java.lang.reflect.Member;
import java.util.ArrayDeque;
import java.util.Deque;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Groups the hooks of one feature (or one optional part of it) so that a failure part-way
 * through installation removes the hooks already installed, instead of leaving the host with a
 * partial modification. Undo actions run in reverse installation order.
 *
 * <p>Not thread-safe: one transaction belongs to one installing thread.</p>
 */
public final class HookTransaction {
    private final Deque<Runnable> undo = new ArrayDeque<>();
    private boolean closed;

    public XC_MethodHook.Unhook hook(Member method, XC_MethodHook callback) {
        ensureOpen();
        XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(method, callback);
        undo.push(unhook::unhook);
        return unhook;
    }

    public void hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        ensureOpen();
        for (XC_MethodHook.Unhook unhook : XposedBridge.hookAllConstructors(type, callback)) {
            undo.push(unhook::unhook);
        }
    }

    /** Registers a reversible side effect other than a hook (for example a published flag). */
    public void onRollback(Runnable action) {
        ensureOpen();
        undo.push(action);
    }

    /** Moves a finished sub-transaction's undo actions into this one. */
    public void adopt(HookTransaction part) {
        ensureOpen();
        part.ensureOpen();
        // part.undo is newest-first; keep that order on top of this transaction's stack.
        var reversed = new ArrayDeque<Runnable>();
        for (Runnable action : part.undo) reversed.push(action);
        for (Runnable action : reversed) undo.push(action);
        part.undo.clear();
        part.closed = true;
    }

    public void commit() {
        ensureOpen();
        undo.clear();
        closed = true;
    }

    /** Removes everything installed so far; every undo action runs even if an earlier one fails. */
    public void rollback() {
        if (closed) return;
        closed = true;
        while (!undo.isEmpty()) {
            try {
                undo.pop().run();
            } catch (Throwable ignored) {
                // Continue: a failed unhook must not keep the remaining hooks installed.
            }
        }
    }

    public int size() {
        return undo.size();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Hook transaction already finished");
    }
}
