package com.waenhancer.theme;

import java.util.Objects;

/** Producer-only revision. Row positions never belong in this key. */
public final class BackdropEpoch {
    private Object key;
    private long revision;
    private boolean ready, released;
    public long revision() { return revision; }
    public boolean ready() { return ready && !released; }
    public boolean update(Object next) {
        if (released || Objects.equals(key,next)) return false;
        key=next; revision++; ready=false; return true;
    }
    public boolean complete(long token, boolean success) {
        if (released || token!=revision || key==null) return false;
        ready=success; return true;
    }
    public void release() { released=true; ready=false; key=null; revision++; }
}
