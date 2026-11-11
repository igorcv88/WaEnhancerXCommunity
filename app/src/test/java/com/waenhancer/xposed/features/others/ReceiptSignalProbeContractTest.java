package com.waenhancer.xposed.features.others;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Source contracts for the opt-in research probe; device behavior remains to be verified. */
public class ReceiptSignalProbeContractTest {
    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/" + path)),
                StandardCharsets.UTF_8);
    }

    @Test public void watchIsExplicitTemporaryAndDoesNotModifyReceipts() throws Exception {
        String s = source("com/waenhancer/xposed/features/others/ReceiptSignalProbe.java");
        assertTrue(s.contains("receipt_signal_probe"));
        assertTrue(s.contains("prefs.getBoolean(PREF, false)"));
        assertTrue(s.contains("10 * 60 * 1000L"));
        assertTrue(s.contains("MAX_RECORDS = 128"));
        assertTrue(s.contains("MAX_OBSERVED = 512"));
        assertTrue(s.contains("window.records + window.unmatched >= MAX_OBSERVED"));
        assertTrue(s.contains("w.messageIds.size() < MAX_TARGETS"));
        assertTrue(s.contains("MAX_TARGETS = 12"));
        assertTrue(s.contains("if (w.messageIds.contains(messageId)) return;"));
        assertFalse(s.contains("param.setResult("));
        assertFalse(s.contains("setRead("));
        assertFalse(s.contains("sendBroadcast("));
        assertFalse(s.contains("getMessageStr("));
        assertFalse(s.contains("getContentDescription("));
    }

    @Test public void nextOutgoingIsSelectedWithoutBlueOnReplyDependency() throws Exception {
        String s = source("com/waenhancer/xposed/features/general/SeenTick.java");
        int p = s.indexOf("if (ReceiptSignalProbe.wantsTarget(prefs))");
        int q = s.indexOf("if (!prefs.getBoolean(\"blueonreply\", false)) return;", p);
        assertTrue(p > 0 && q > p);
        String capture = s.substring(p, q);
        assertTrue(capture.contains("findSendJob(param, messageSendClass)"));
        assertTrue(capture.contains("!peer.isGroup() && !peer.isStatus()"));
        assertTrue(capture.contains("ReceiptSignalProbe.selectOutgoing("));
    }

    @Test public void allReceiptTypesAreInspectedBeforeToastFiltering() throws Exception {
        String s = source("com/waenhancer/xposed/features/others/ToastViewer.java");
        int probe = s.indexOf("ReceiptSignalProbe.inspectReceiptInsert(prefs, param.args[0]);");
        int filter = s.indexOf("if (type != 13) continue;");
        assertTrue(probe > 0 && filter > probe);
        String watcher = source("com/waenhancer/xposed/features/others/ReceiptSignalProbe.java");
        assertTrue(watcher.contains("matchedSlot"));
        assertTrue(watcher.contains("indexForMessage(w"));
        assertTrue(watcher.contains("indexForRow(w"));
        assertTrue(watcher.contains("chatMatch"));
        assertTrue(watcher.contains("stage=unmatched_aggregate"));
        assertTrue(watcher.contains("stage=receipt_insert"));
    }

    @Test public void reactionSendIsSeparateFromIncomingReadAcknowledgement() throws Exception {
        String src = source("com/waenhancer/xposed/features/others/ReceiptSignalProbe.java");
        String toast = source("com/waenhancer/xposed/features/others/ToastViewer.java");
        assertTrue(toast.contains("ReceiptSignalProbe.installReactionSendProbe(classLoader, prefs);"));
        assertTrue(src.contains("Unobfuscator.loadActionUser(loader)"));
        assertTrue(src.contains("new Class[]{FMessageWpp.TYPE, String.class, boolean.class}"));
        assertTrue(src.contains("stage=reaction_attempt"));
        assertTrue(src.contains("original_direction="));
        assertTrue(src.contains("if (w == null || w.chatJid == null || referencedMessage == null) return;"));
        assertFalse(src.contains("Log.i(TAG, emoji"));
    }

    @Test public void noRawIdentifiersOrContentEnterLogcat() throws Exception {
        String s = source("com/waenhancer/xposed/features/others/ReceiptSignalProbe.java");
        assertFalse(s.contains("Log.i(TAG, messageId"));
        assertFalse(s.contains("Log.i(TAG, primaryJid"));
        assertFalse(s.contains("Log.i(TAG, incoming"));
        assertFalse(s.contains("Log.i(TAG, event.toString()"));
        assertTrue(s.contains("w.messageIds.indexOf(incoming)"));
        assertTrue(s.contains("stage=receipt_schema"));
        assertTrue(s.contains("field_count="));
    }
}
