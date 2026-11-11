package com.waenhancer.xposed.features.others;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Opt-in, local, metadata-only investigation of receipt store updates.
 * Anchors one direct chat and up to 12 outgoing messages (in RAM, never in logs).
 * Also observes calls to the already-known WhatsApp reaction-sending method when
 * that hook is available; these are attempts, NOT receipt or reaction-view proofs.
 *
 * There is no network interception and no modification to WhatsApp messages,
 * receipt jobs, native tick colours, or privacy settings.
 */
public final class ReceiptSignalProbe {
    private static final String TAG = "WaEXReceiptProbe";
    public static final String PREF = "receipt_signal_probe";
    private static final long MAX_MS = 10 * 60 * 1000L;
    private static final int MAX_RECORDS = 128;
    private static final int MAX_OBSERVED = 512;
    private static final int MAX_TARGETS = 12;
    private static final int MAX_EVENTS_PER_CALLBACK = 32;
    private static Window window;

    private ReceiptSignalProbe() {}

    private static final class Window {
        final long start;
        final ArrayList<String> messageIds = new ArrayList<>(MAX_TARGETS);
        final ArrayList<Long> rowIds = new ArrayList<>(MAX_TARGETS);
        final Set<String> schemaLogged = new HashSet<>();
        String chatJid;
        String otherChatJid;
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
            Log.i(TAG, "stage=armed scope=next_direct_chat target_limit=12 duration_sec=600");
        }
        if (now - window.start >= MAX_MS || window.records >= MAX_RECORDS
                || window.records + window.unmatched >= MAX_OBSERVED) {
            if (!window.expiredLogged) {
                window.expiredLogged = true;
                Log.i(TAG, "stage=stopped reason=" + ((window.records >= MAX_RECORDS
                        || window.records + window.unmatched >= MAX_OBSERVED) ? "budget" : "timeout")
                        + " records=" + window.records + " unmatched=" + window.unmatched
                        + " tracked=" + window.messageIds.size());
            }
            return null;
        }
        return window;
    }

    /** Called from the existing send-job hook, never from a broad network hook. */
    public static synchronized boolean wantsTarget(SharedPreferences prefs) {
        Window w = active(prefs);
        return w != null && w.messageIds.size() < MAX_TARGETS;
    }

    /**
     * Select the first direct conversation and track subsequent outbound messages
     * there. Never silently switch the watched chat if another chat is opened.
     */
    public static synchronized void selectOutgoing(SharedPreferences prefs, String messageId,
                                                    String primaryJid, String secondaryJid,
                                                    long rowId) {
        Window w = active(prefs);
        if (w == null || w.messageIds.size() >= MAX_TARGETS
                || messageId == null || messageId.isEmpty()
                || primaryJid == null || primaryJid.isEmpty()) return;
        if (w.chatJid == null) {
            w.chatJid = primaryJid;
            w.otherChatJid = secondaryJid;
        } else if (!matches(w, primaryJid) && !matches(w, secondaryJid)) {
            return;
        }
        if (w.messageIds.contains(messageId)) return;
        int slot = w.messageIds.size();
        w.messageIds.add(messageId);
        w.rowIds.add(rowId);
        Log.i(TAG, "stage=target_selected slot=" + slot
                + " elapsed_ms=" + (SystemClock.elapsedRealtime() - w.start)
                + " row_linked=" + (rowId > 0)
                + " scope=direct receipt_tracking=passive");
    }

    /**
     * Called only for the known WhatsApp reaction-sending method's invocation.
     * The referenced message may have been sent by the *other* participant;
     * it must not be confused with any outgoing-message receipt.
     */
    public static synchronized void observeReactionAttempt(SharedPreferences prefs,
                                                             Object referencedMessage,
                                                             boolean adding) {
        Window w = active(prefs);
        if (w == null || w.chatJid == null || referencedMessage == null) return;
        try {
            FMessageWpp msg = new FMessageWpp(referencedMessage);
            FMessageWpp.Key key = msg.getKey();
            if (key == null || !matchesChat(w, key.remoteJid)) return;
            Log.i(TAG, "stage=reaction_attempt elapsed_ms="
                    + (SystemClock.elapsedRealtime() - w.start)
                    + " original_direction=" + (key.isFromMe ? "outgoing" : "incoming")
                    + " action=" + (adding ? "add" : "remove")
                    + " target_is_sent_message=" + (indexForMessage(w, key.messageID) >= 0));
        } catch (Throwable ignored) {
            // Diagnostic only. Never affect the reaction operation.
        }
    }

    /**
     * Hook the WhatsApp ActionUser method already used by WppCore.sendReaction.
     * This may or may not be the path for native UI reactions on the installed
     * WhatsApp version. A missing reaction_attempt must be treated as "not observed".
     */
    public static void installReactionSendProbe(ClassLoader loader, SharedPreferences prefs) {
        if (prefs == null || !prefs.getBoolean(PREF, false)) return;
        try {
            Class<?> owner = Unobfuscator.loadActionUser(loader);
            Method method = ReflectionUtils.findMethodUsingFilterIfExists(owner,
                    m -> m.getParameterCount() == 3
                            && Arrays.equals(m.getParameterTypes(),
                                    new Class[]{FMessageWpp.TYPE, String.class, boolean.class}));
            if (method == null) {
                Log.i(TAG, "stage=reaction_hook status=not_found");
                return;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 3) return;
                    observeReactionAttempt(prefs, param.args[0], Boolean.TRUE.equals(param.args[2]));
                }
            });
            Log.i(TAG, "stage=reaction_hook status=installed method_shape=message_string_boolean");
        } catch (Throwable failure) {
            Log.i(TAG, "stage=reaction_hook status=unsupported exception="
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * Receives already-decoded insert-receipt callback objects. Every supported
     * numeric code (including unrecognized ones) is eligible for observation.
     * Unrelated contacts are counted, never printed or inspected recursively.
     */
    public static void inspectReceiptInsert(SharedPreferences prefs, Object callbackArg) {
        Window w = active(prefs);
        if (w == null || callbackArg == null || w.messageIds.isEmpty()) return;
        Collection<?> events = callbackArg instanceof Collection
                ? (Collection<?>) callbackArg : Collections.singleton(callbackArg);
        int visited = 0;
        for (Object event : events) {
            if (event == null || visited++ >= MAX_EVENTS_PER_CALLBACK) break;
            inspectOne(prefs, event);
        }
    }

    private static synchronized void inspectOne(SharedPreferences prefs, Object event) {
        Window w = active(prefs);
        if (w == null || w.messageIds.isEmpty()) return;
        int matchedSlot = -1;
        boolean chatMatch = false;
        StringBuilder ints = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        int seen = 0, numeric = 0;
        for (Class<?> c = event.getClass(); c != null && c != Object.class && seen < 32;
             c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (++seen > 32) break;
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (fields.length() < 240) {
                    if (fields.length() > 0) fields.append(',');
                    fields.append(field.getName()).append('/').append(type.getSimpleName());
                }
                try {
                    field.setAccessible(true);
                    if (type == String.class) {
                        // Equality comparisons only: opaque values never leave this process.
                        int found = indexForMessage(w, (String) field.get(event));
                        if (found >= 0) matchedSlot = found;
                    } else if (type == int.class) {
                        if (numeric++ < 8) {
                            if (ints.length() > 0) ints.append(',');
                            ints.append(field.getName()).append(':').append(field.getInt(event));
                        }
                    } else if (type == long.class) {
                        int found = indexForRow(w, field.getLong(event));
                        if (found >= 0) matchedSlot = found;
                    } else if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isAssignableFrom(type)) {
                        Object raw = field.get(event);
                        if (raw != null) {
                            FMessageWpp msg = new FMessageWpp(raw);
                            FMessageWpp.Key key = msg.getKey();
                            if (key != null) {
                                int found = indexForMessage(w, key.messageID);
                                if (found >= 0) matchedSlot = found;
                                chatMatch |= matchesChat(w, key.remoteJid);
                            }
                            int found = indexForRow(w, msg.getRowId());
                            if (found >= 0) matchedSlot = found;
                        }
                    } else if (FMessageWpp.Key.TYPE != null
                            && FMessageWpp.Key.TYPE.isAssignableFrom(type)) {
                        Object raw = field.get(event);
                        if (raw != null) {
                            FMessageWpp.Key key = new FMessageWpp.Key(raw);
                            int found = indexForMessage(w, key.messageID);
                            if (found >= 0) matchedSlot = found;
                            chatMatch |= matchesChat(w, key.remoteJid);
                        }
                    } else if (type.getName().contains(".jid.")) {
                        Object jid = field.get(event);
                        if (jid != null) chatMatch |= matchesChat(w, new FMessageWpp.UserJid(jid));
                    }
                } catch (Throwable ignored) {
                    // Obfuscated layouts may change between WhatsApp versions.
                }
            }
        }
        if (matchedSlot < 0 && !chatMatch) {
            w.unmatched++;
            if (w.unmatched % 25 == 0)
                Log.i(TAG, "stage=unmatched_aggregate count=" + w.unmatched);
            return;
        }
        w.records++;
        String schema = safeTypeName(event.getClass());
        if (w.schemaLogged.add(schema))
            Log.i(TAG, "stage=receipt_schema schema=" + schema + " fields=" + fields);
        Log.i(TAG, "stage=receipt_insert elapsed_ms=" + (SystemClock.elapsedRealtime() - w.start)
                + " match=" + (matchedSlot >= 0 ? "message_or_row" : "chat_only")
                + " slot=" + matchedSlot
                + " schema=" + schema
                + " ints=" + ints + " field_count=" + seen);
    }

    private static int indexForMessage(Window w, String incoming) {
        return incoming == null ? -1 : w.messageIds.indexOf(incoming);
    }

    private static int indexForRow(Window w, long incoming) {
        return incoming <= 0 ? -1 : w.rowIds.indexOf(incoming);
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
