package com.waenhancer.theme;

/** Coalesce native property rebinds independently of geometry; no work on setter stacks. */
public final class ComposerRebind {
    private boolean pending;
    public boolean request() { if (pending) return false; pending = true; return true; }
    public boolean consume() { boolean result = pending; pending = false; return result; }
    public void reset() { pending = false; }
}
