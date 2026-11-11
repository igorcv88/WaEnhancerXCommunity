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
        assertTrue(s.contains("w.messageId == null"));
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
        assertTrue(watcher.contains("messageMatch"));
        assertTrue(watcher.contains("rowMatch"));
        assertTrue(watcher.contains("chatMatch"));
        assertTrue(watcher.contains("stage=unmatched_aggregate"));
        assertTrue(watcher.contains("stage=receipt_insert"));
    }

    @Test public void noRawIdentifiersOrContentEnterLogcat() throws Exception {
        String s = source("com/waenhancer/xposed/features/others/ReceiptSignalProbe.java");
        assertFalse(s.contains("Log.i(TAG, messageId"));
        assertFalse(s.contains("Log.i(TAG, primaryJid"));
        assertFalse(s.contains("Log.i(TAG, incoming"));
        assertFalse(s.contains("Log.i(TAG, event.toString()"));
        assertTrue(s.contains("messageMatch |= w.messageId.equals("));
        assertTrue(s.contains("field_count="));
    }
}
