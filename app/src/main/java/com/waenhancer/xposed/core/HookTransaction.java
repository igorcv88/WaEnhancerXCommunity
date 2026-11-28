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
 * <p>Callbacks are gated: until {@link #commit()} a hooked method runs unchanged, so no callback
 * acts while installation is incomplete or after a rollback. Side effects that must exist before
 * the commit are registered explicitly ({@link #onRollback}).</p>
 *
 * <p>Not thread-safe: one transaction belongs to one installing thread.</p>
 */
public final class HookTransaction {
    private final Deque<Runnable> undo = new ArrayDeque<>();
    private final HookGate gate = new HookGate();
    private boolean closed;

    public XC_MethodHook.Unhook hook(Member method, XC_MethodHook callback) {
        ensureOpen();
        XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(method, gated(callback));
        undo.push(unhook::unhook);
        return unhook;
    }

    public void hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        ensureOpen();
        for (XC_MethodHook.Unhook unhook : XposedBridge.hookAllConstructors(type, gated(callback))) {
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
        part.gate.follow(gate);
    }

    public void commit() {
        ensureOpen();
        undo.clear();
        closed = true;
        gate.open();
    }

    /** Whether this transaction's callbacks act (after commit, or the adopting one's commit). */
    public boolean committed() {
        return gate.isOpen();
    }

    private XC_MethodHook gated(XC_MethodHook callback) {
        HookGate own = gate;
        return new ForwardingHook(callback) {
            @Override
            boolean applies(MethodHookParam param) {
                return own.isOpen();
            }
        };
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
