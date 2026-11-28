package com.waenhancer.xposed.core;

/**
 * Opens a transaction's callbacks only after its commit. A hook is live in the host as soon as
 * it is registered, so without the gate a callback could run (and change host state) while a
 * later installation step is still pending or after the transaction was rolled back. An adopted
 * part follows the gate of the transaction that adopted it.
 */
final class HookGate {
    private volatile boolean open;
    private volatile HookGate parent;

    boolean isOpen() {
        if (open) return true;
        HookGate up = parent;
        return up != null && up.isOpen();
    }

    void open() {
        open = true;
    }

    void follow(HookGate owner) {
        if (owner == this) throw new IllegalArgumentException("a gate cannot follow itself");
        parent = owner;
    }
}
