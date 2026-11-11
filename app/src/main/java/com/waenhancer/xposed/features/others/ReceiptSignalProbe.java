package com.waenhancer.xposed.features.others;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.waenhancer.xposed.core.components.FMessageWpp;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;

/**
 * Temporary, opt-in protocol receipt probe. It tracks the next outgoing 1:1 message
 * in this WhatsApp process and inspects only metadata on already-decoded receipt-store
 * callbacks. No network interception, message contents, phone numbers, JIDs, message
 * IDs, media, or reaction text are logged. This does not infer or change read state.
 *
 * The window expires after ten minutes per process. Toggle off/on to rearm; if the
 * WhatsApp process restarts with the switch on, a new window starts.
 */
public final class ReceiptSignalProbe {
    private static final String TAG = "WaEXReceiptProbe";
    public static final String PREF = "receipt_signal_probe";
    private static final long MAX_MS = 10 * 60 * 1000L;
    private static final int MAX_RECORDS = 128;
    private static final int MAX_OBSERVED = 512;
    private static Window window;

    private ReceiptSignalProbe() {}

    private static final class Window {
        final long start;
        String messageId;
        String chatJid;
        String otherChatJid;
        long rowId = -1;
        int records;
        int unmatched;
        boolean expiredLogged;

        Window(long start) { this.start = start; }
    }

    private static synchronized Window active(SharedPreferences prefs) {
        boolean enabled;
        try { enabled = prefs != null && prefs.getBoolean(PREF, false); }
        catch (Throwable ignored) { enabled = false; }
        if (!enabled) {
            window = null;
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (window == null) {
            window = new Window(now);
            Log.i(TAG, "stage=armed scope=next_outgoing_direct duration_sec=600");
        }
        if (now - window.start >= MAX_MS || window.records >= MAX_RECORDS
                || window.records + window.unmatched >= MAX_OBSERVED) {
            if (!window.expiredLogged) {
                window.expiredLogged = true;
                Log.i(TAG, "stage=stopped reason=" + ((window.records >= MAX_RECORDS
                        || window.records + window.unmatched >= MAX_OBSERVED) ? "budget" : "timeout")
                        + " records=" + window.records + " unmatched=" + window.unmatched);
            }
            return null;
        }
        return window;
    }

    /** Called from the existing send-job hook, never from a broad network hook. */
    public static synchronized boolean wantsTarget(SharedPreferences prefs) {
        Window w = active(prefs);
        return w != null && w.messageId == null;
    }

    public static synchronized void selectOutgoing(SharedPreferences prefs, String messageId,
                                                    String primaryJid, String secondaryJid,
                                                    long rowId) {
        Window w = active(prefs);
        if (w == null || w.messageId != null || messageId == null || messageId.isEmpty()
                || primaryJid == null || primaryJid.isEmpty()) return;
        w.messageId = messageId;
        w.chatJid = primaryJid;
        w.otherChatJid = secondaryJid;
        w.rowId = rowId;
        Log.i(TAG, "stage=target_selected row_linked=" + (rowId > 0)
                + " scope=direct receipt_tracking=passive");
    }

    /**
     * Receives the *same already-decoded* callback objects as ToastViewer.
     * Unmatched event types count toward an aggregate, but no unrelated per-contact
     * metadata is emitted. Unknown integer fields on matching rows are preserved.
     */
    public static void inspectReceiptInsert(SharedPreferences prefs, Object callbackArg) {
        Window w = active(prefs);
        if (w == null || callbackArg == null || w.messageId == null) return;
        Collection<?> events = callbackArg instanceof Collection
                ? (Collection<?>) callbackArg : Collections.singleton(callbackArg);
        int visited = 0;
        for (Object event : events) {
            if (event == null || visited++ >= 32) break;
            inspectOne(prefs, event);
        }
    }

    private static synchronized void inspectOne(SharedPreferences prefs, Object event) {
        Window w = active(prefs);
        if (w == null || w.messageId == null) return;
        boolean messageMatch = false, rowMatch = false, chatMatch = false;
        StringBuilder ints = new StringBuilder();
        int seen = 0, numeric = 0;
        for (Class<?> c = event.getClass(); c != null && c != Object.class && seen < 32;
             c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (++seen > 32) break;
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Class<?> type = field.getType();
                    if (type == String.class) {
                        // Compare an opaque identifier in RAM only. Never log its value.
                        messageMatch |= w.messageId.equals(field.get(event));
                    } else if (type == int.class) {
                        if (numeric++ < 8) {
                            if (ints.length() > 0) ints.append(',');
                            ints.append(field.getName()).append(':').append(field.getInt(event));
                        }
                    } else if (type == long.class) {
                        rowMatch |= w.rowId > 0 && w.rowId == field.getLong(event);
                    } else if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isAssignableFrom(type)) {
                        Object raw = field.get(event);
                        if (raw != null) {
                            FMessageWpp msg = new FMessageWpp(raw);
                            FMessageWpp.Key key = msg.getKey();
                            if (key != null) {
                                messageMatch |= w.messageId.equals(key.messageID);
                                chatMatch |= matchesChat(w, key.remoteJid);
                            }
                            rowMatch |= w.rowId > 0 && w.rowId == msg.getRowId();
                        }
                    } else if (type.getName().contains(".jid.")) {
                        Object jid = field.get(event);
                        if (jid != null) chatMatch |= matchesChat(w, new FMessageWpp.UserJid(jid));
                    }
                } catch (Throwable ignored) {
                    // Unknown host versions are observable only where extraction is verified.
                }
            }
        }
        if (!messageMatch && !rowMatch && !chatMatch) {
            w.unmatched++;
            if (w.unmatched % 25 == 0)
                Log.i(TAG, "stage=unmatched_aggregate count=" + w.unmatched);
            return;
        }
        w.records++;
        Log.i(TAG, "stage=receipt_insert elapsed_ms=" + (SystemClock.elapsedRealtime() - w.start)
                + " match=" + (messageMatch ? "message" : rowMatch ? "row" : "chat_only")
                + " schema=" + safeTypeName(event.getClass())
                + " ints=" + ints + " field_count=" + seen);
    }

    private static boolean matchesChat(Window w, FMessageWpp.UserJid jid) {
        if (jid == null || jid.isNull()) return false;
        return matches(w, jid.getPhoneRawString()) || matches(w, jid.getUserRawString());
    }

    private static boolean matches(Window w, String incoming) {
        return incoming != null && (incoming.equals(w.chatJid)
                || incoming.equals(w.otherChatJid));
    }

    private static String safeTypeName(Class<?> clazz) {
        String name = clazz.getName();
        return name.length() > 80 ? name.substring(0, 80) : name;
    }
}
