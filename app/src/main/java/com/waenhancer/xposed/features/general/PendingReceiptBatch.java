package com.waenhancer.xposed.features.general;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits pending hidden read receipts for one chat into the bounded set that is re-sent and the
 * rows that are only marked released.
 *
 * <p>Adapted from Dev4Mod/WaEnhancer commit d2c71a5 (GPL-3.0): only the newest
 * {@link #MAX_SEND} resolvable messages are sent as one blue-tick release; older and
 * unresolvable rows are marked released in one transaction so they are not looked up and retried
 * on every chat open. Input rows must be ordered oldest first.</p>
 */
public final class PendingReceiptBatch<T> {
    public static final int MAX_SEND = 50;

    public final List<T> send;
    public final List<String> markOnly;

    private PendingReceiptBatch(List<T> send, List<String> markOnly) {
        this.send = Collections.unmodifiableList(send);
        this.markOnly = Collections.unmodifiableList(markOnly);
    }

    /**
     * @param ids      pending message IDs, oldest first
     * @param resolved resolved message for each ID, or {@code null} when it no longer exists
     */
    public static <T> PendingReceiptBatch<T> split(List<String> ids, List<T> resolved, int maxSend) {
        if (ids.size() != resolved.size()) throw new IllegalArgumentException("size mismatch");
        int resolvable = 0;
        for (T item : resolved) if (item != null) resolvable++;
        int skipResolved = Math.max(0, resolvable - Math.max(0, maxSend));
        List<T> send = new ArrayList<>(Math.min(resolvable, maxSend));
        List<String> markOnly = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            T item = resolved.get(i);
            if (item == null) {
                markOnly.add(ids.get(i));
            } else if (skipResolved > 0) {
                skipResolved--;
                markOnly.add(ids.get(i));
            } else {
                send.add(item);
            }
        }
        return new PendingReceiptBatch<>(send, markOnly);
    }
}
