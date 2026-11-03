package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Device report 2026-11-03 (after #137): glass lost on a tap and on some other movements, and a
 * blink when a chat opens. Each case below is a path that left a row native although glass was
 * possible; the device trace confirms which ones occur.
 */
public class BubbleGlassStabilityTest {
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static final String GLASS = "src/main/java/com/waenhancer/theme/NativeBubbleGlass.java";
    private static final String APP = "src/main/java/com/waenhancer/xposed/features/customization/AppLiquidGlass.java";
    private static final String SOURCE = "src/main/java/com/waenhancer/theme/ConversationBackdropSource.java";

    // --- Slots belong to positions in a row's recording, not to drawable instances --------

    @Test public void slotsRestartWithEachRecording() {
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        Object first = new Object(), second = new Object();
        assertEquals(0, row.claim(first));
        assertEquals(1, row.claim(first));
        assertEquals("a new recording reuses slot 0", 0, row.claim(second));
        assertEquals(1, row.claim(second));
    }

    @Test public void manyRebindsNeverFillARow() {
        // Previously: one slot per drawable instance, freed only by GC. A row rebound with new or
        // cached factory drawables five times painted natively (SLOT_FULL) until a collection.
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        for (int recording = 0; recording < 100; recording++) {
            Object scope = new Object();
            assertEquals(0, row.claim(scope));
        }
    }

    @Test public void oneRecordingIsStillBounded() {
        NativeBubbleGlass.RowSlots row = new NativeBubbleGlass.RowSlots();
        Object scope = new Object();
        for (int i = 0; i < NativeBubbleGlass.MAX_SLOTS_PER_ROW; i++) assertEquals(i, row.claim(scope));
        assertEquals(-1, row.claim(scope));
    }

    @Test public void slotsDoNotHoldRowsOrDrawables() throws IOException {
        String glass = read(GLASS);
        assertTrue("the scope holds the row, the map's weak key",
                glass.contains("WeakReference<Object> recording = new WeakReference<>(null);"));
        assertFalse(glass.contains("slot.drawable.get()"));
        assertTrue("another drawable at the same size is measured again",
                glass.contains("String measure = System.identityHashCode(bubble) + \":\""));
        assertTrue(glass.contains("Slot slot = slotFor(host, entry);"));
    }

    // --- A transiently unsupported plan keeps the texture ---------------------------------

    @Test public void transientUnsupportedPlanKeepsGlass() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void syncSharedBubbles()");
        String sync = app.substring(start, app.indexOf("void wallpaperChanged(View target)", start));
        int keep = sync.indexOf("if (unsupported!=null && bubbleSource.texture()!=null && !bubbleSource.failed()");
        assertTrue(keep > 0);
        assertTrue("only for the wallpaper the texture shows", sync.indexOf("identity.equals(textureIdentity)", keep) > keep);
        assertTrue("before the fallback and the withdrawing update",
                keep < sync.indexOf("wallpaperFallback.request(") && keep < sync.indexOf("bubbleSource.update(plan,"));
        assertTrue(sync.contains("if (plan.unsupportedReason()==null) textureIdentity=identity;"));
    }

    @Test public void withdrawalIsVisibleInTraces() throws IOException {
        String glass = read(GLASS);
        assertTrue(glass.contains("if (texture == null) { reset(\"texture-withdrawn\"); return; }"));
        assertTrue(glass.contains("\"BUBBLE_GLASS_RESET\""));
        assertTrue("a row that had glass and records native is reported",
                glass.contains("if (glassRows.remove(host)) { BubbleGlassTelemetry.lost(reason.name(), host); notifyChanged(host); }"));
        assertTrue(glass.contains("if (slot == null) return lost(host, BubbleGlassTelemetry.Native.SLOT_FULL);"));
    }

    // --- New rows are hosted from their first draw ----------------------------------------

    @Test public void rowsWithAVerifiedIdAreHostedWithoutWaitingForDiscovery() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void observeNativeRowDraw(View row)");
        String body = app.substring(start, app.indexOf("afterHookedMethod", start));
        assertTrue(body.contains("rememberRowId(row.getId());"));
        assertTrue(body.contains("if (!messageRow(host) || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF) return;"));
        assertTrue("captures are still skipped first",
                body.indexOf("GlassPane.isCaptureCanvas") < body.indexOf("messageRow(host)"));
    }

    // --- Opening a chat with a known wallpaper look shows glass at once -------------------

    // --- Scrolling: cached copies of a factory bubble (device 2026-11-03) ------------------

    @Test public void cachedBubbleCopiesAreAdoptedOnlyOnVerifiedRows() throws IOException {
        String app = read(APP);
        assertTrue("the factory records the ConstantState it returns",
                app.contains("if (state!=null) bubbleStateSides.put(state, (Integer) param.args[0]);"));
        int start = app.indexOf("private Integer adoptBubbleCopy(");
        String body = app.substring(start, app.indexOf("\n    }\n", start));
        assertTrue("only inside a verified row draw",
                body.indexOf("NativeBubbleGlass.hostFor((Canvas) canvas) == null) return null;") < body.indexOf("bubbleStateSides.get(state)"));
        assertTrue(body.contains("bubbleSides.put(bubble, direction);"));
        int hook = app.indexOf("private void drawNativeBubble(");
        String draw = app.substring(hook, app.indexOf("\n    }\n", hook));
        assertTrue(draw.indexOf("adoptBubbleCopy(bubble") < draw.indexOf("Boolean outgoing="));
    }

    // --- Quieter rim (owner review 2026-11-03) --------------------------------------------

    @Test public void bubbleRimIsAFractionOfTheChromeRim() throws IOException {
        assertTrue(NativeBubbleGlass.BUBBLE_RIM_GAIN > 0f && NativeBubbleGlass.BUBBLE_RIM_GAIN < 1f);
        assertTrue(NativeBubbleGlass.BUBBLE_HIGHLIGHT_GAIN > 0f && NativeBubbleGlass.BUBBLE_HIGHLIGHT_GAIN < 1f);
        assertTrue(NativeBubbleGlass.BUBBLE_STROKE_DP < 1.25f);
        String glass = read(GLASS);
        assertTrue("scales the user's rim setting instead of replacing it",
                glass.contains("params.contourGain *= BUBBLE_RIM_GAIN;"));
        assertTrue(glass.contains("params.highlight *= BUBBLE_HIGHLIGHT_GAIN;"));
    }

    // --- Reply snippets readable on glass -------------------------------------------------

    @Test public void replySnippetFollowsTheRowsGlassAndIsRestored() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void syncReplyText(View row)");
        String body = app.substring(start, app.indexOf("private void tintText(", start));
        assertTrue("snippet only; the sender title keeps its participant colour",
                body.contains("rowChild(row, \"quoted_text\")") && !body.contains("quoted_title"));
        assertTrue(body.contains("bubbleGlass.hasGlass(row)"));
        int tint = app.indexOf("private void tintText(");
        String text = app.substring(tint, app.indexOf("private void tintIcon(", tint));
        assertTrue("a row without glass gets WhatsApp's colour back",
                text.contains("if (state != null && current == state[1]) text.setTextColor(state[0]);"));
        assertTrue(app.contains("if (contrastPending.add(row)) row.post(() -> { contrastPending.remove(row); if (!closed) syncReplyText(row); });"));
        assertTrue(app.contains("restoreReplyCorners();\n            restoreReplyTexts();"));
        String glass = read(GLASS);
        assertTrue("a sent message on a recycled row drops the glass state",
                glass.contains("public void nativeRow(View host) {"));
        assertTrue(app.contains("if (owner!=null && owner.bubbleGlass!=null) owner.bubbleGlass.nativeRow(host);"));
    }

    @Test public void voiceNoteControlsFollowTheGlassAndAreRestored() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void syncReplyText(View row)");
        String body = app.substring(start, app.indexOf("private void tintText(", start));
        assertTrue(body.contains("rowChild(row, \"control_btn\")"));
        assertTrue(body.contains("tintText(rowChild(row, \"description\"), glass, secondary);"));
        assertTrue("seek thumb keeps its accent", !body.contains("audio_seekbar"));
        int lift = app.indexOf("private void liftLayer(View view, boolean glass)");
        String layer = app.substring(lift, app.indexOf("private void restoreReplyTexts()", lift));
        assertTrue("original layer type restored", layer.contains("view.setLayerType(original, null);"));
        int restore = app.indexOf("private void restoreReplyTexts()");
        String all = app.substring(restore, app.indexOf("private void restoreReplyCorners()", restore));
        assertTrue(all.contains("icon.setImageTintList(entry.getValue()[0]);"));
        assertTrue(all.contains("entry.getKey().setLayerType(entry.getValue(), null);"));
    }

    @Test public void contrastIsReappliedAfterEveryGlassRecording() throws IOException {
        // Device 2026-11-03: voice notes and replies were white or grey at random; WhatsApp
        // rebinds rows that keep their glass and resets its colours without a glass change.
        String glass = read(GLASS);
        assertTrue(glass.contains("glassRows.add(host);\n        notifyChanged(host);"));
        String app = read(APP);
        int lift = app.indexOf("private void liftLayer(View view, boolean glass)");
        String layer = app.substring(lift, app.indexOf("private void restoreReplyTexts()", lift));
        assertTrue("a layer type reset by WhatsApp is lifted again",
                layer.contains("if (original != null && view.getLayerType() == View.LAYER_TYPE_HARDWARE) return;"));
    }

    @Test public void callAndViewOnceRowsFollowTheGlass() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void syncReplyText(View row)");
        String body = app.substring(start, app.indexOf("private void tintText(", start));
        assertTrue(body.contains("rowChild(row, \"call_type_icon\")"));
        assertTrue(body.contains("tintText(rowChild(row, \"call_log_subtitle\"), glass, secondary);"));
        assertTrue(body.contains("rowChild(row, \"view_once_media_container_large\")"));
        int clear = app.indexOf("private void clearFill(Drawable fill, boolean glass)");
        String fill = app.substring(clear, app.indexOf("private void tintTree(", clear));
        assertTrue("only flat fills, restorable", fill.contains("instanceof android.graphics.drawable.ColorDrawable")
                && fill.contains("fill.setAlpha(original);"));
        int restore = app.indexOf("private void restoreReplyTexts()");
        String all = app.substring(restore, app.indexOf("private void restoreReplyCorners()", restore));
        assertTrue(all.contains("entry.getKey().setAlpha(entry.getValue());"));
    }

    @Test public void anyChildOfAVerifiedMessageListIsARow() throws IOException {
        // Device 2026-11-03: view-once and call rows (other IDs) drew registered incoming
        // bubbles on 1440x222 row canvases with frame=none.
        String app = read(APP);
        assertTrue(app.contains("if (row.getParent() != null && knownRowId(row.getId())) rowLists.put(row.getParent(), Boolean.TRUE);"));
        assertTrue(app.contains("if (!messageRow(host) || bubbleScopeCache == LiquidGlassSettings.BubbleScope.OFF) return;"));
        assertTrue("Codex review on #142: rows registered by the early draw path also teach their list",
                app.contains("if (knownRowId(host.getId()) && host.getParent() != null) rowLists.put(host.getParent(), Boolean.TRUE);"));
        int bind = app.indexOf("private void bindNativeBubbleRow(View row)");
        String body = app.substring(bind, app.indexOf("observeNativeRowDraw(row);", bind));
        assertTrue(body.contains("if (!named && (row.getParent() == null || !rowLists.containsKey(row.getParent()))) return;"));
    }

    @Test public void glassNodesDroppedByHwuiAreRebuilt() throws IOException {
        // AOSP libs/hwui RenderNode: MarkAndSweepRemoved -> onRemovedFromTree ->
        // destroyHardwareResources deletes the display lists of the removed subtree, our
        // lens/wallpaper children included. Keys alone (size, texture) cannot detect that.
        String glass = read(GLASS);
        int start = glass.indexOf("void prepare(Drawable bubble");
        String prepare = glass.substring(start, glass.indexOf("void retexture(Bitmap source, int w, int h)", start));
        assertTrue(prepare.contains("boolean wallpaperDropped = texture == source && !wallpaper.hasDisplayList();"));
        assertTrue(prepare.contains("if (texture != source || wallpaperDropped) retexture(source, sourceW, sourceH);"));
        assertTrue(prepare.contains("boolean lensDropped = lensW >= 0 && !lens.hasDisplayList();"));
        assertTrue(prepare.contains("if (w != lensW || h != lensH || lensDropped) {"));
        assertTrue("the wallpaper child is rebuilt before the lens that draws it",
                prepare.indexOf("retexture(source, sourceW, sourceH);") < prepare.indexOf("lens.beginRecording"));
    }

    // --- Cold start: first chat after a restart (owner choice 2026-11-03, option 2) --------

    @Test public void diskCopyHoldsOnlyTheReplayedWallpaperAndIsBounded() throws IOException {
        String source = read(SOURCE);
        int start = source.indexOf("private static void saveDisk(");
        String save = source.substring(start, source.indexOf("\n    }\n", start));
        assertTrue("never rewritten for the same look", save.contains("if (dir==null || look==null || look.equals(savedLook)) return;"));
        assertTrue("quarter resolution", save.contains("texture.getWidth()/DISK_SCALE"));
        assertEquals(4, ConversationBackdropSource.DISK_SCALE);
        assertTrue("no key for a half-written pair", save.indexOf("key.delete();") < save.indexOf("imageTmp.renameTo(image)"));
        assertTrue("a look exists only for replay plans (copies have no contentKey)",
                source.contains("desiredLook=usable && capture.contentKey!=null ?"));
        assertTrue("saved after the result reaches the UI thread",
                source.indexOf("saveDisk(result.bitmap, look);") > source.indexOf("final Render result=rendered;"));
        String app = read(APP);
        int prep = app.indexOf("private void prepareBubbleDiskCache()");
        String body = app.substring(prep, app.indexOf("\n    }\n", prep));
        assertTrue("WhatsApp's cache directory: OS-clearable, not backed up", body.contains("app.getCacheDir()"));
        assertTrue("deleted when bubble glass is off", body.contains("ConversationBackdropSource.clearDisk("));
    }

    @Test public void diskTextureIsProvisionalAndScaledToTheWindow() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("Bitmap shared=memory ? lastTexture : desiredLook.equals(stored) ? diskTexture : null;"));
        assertTrue(source.contains("texture=shared; textureWidth=capture.width; textureHeight=capture.height;"));
        String glass = read(GLASS);
        assertTrue(glass.contains("else content.drawBitmap(source, null, new Rect(0, 0, w, h), SCALED);"));
        assertTrue(glass.contains("source.textureWidth(), source.textureHeight()"));
    }

    @Test public void rowDrawIsObservedFromStartup() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private void observeRowsEarly()");
        String body = app.substring(start, app.indexOf("\n    }\n", start));
        assertTrue("only when bubble glass can run",
                body.indexOf("BubbleScope.OFF") < body.indexOf("hookRowDraw("));
        assertTrue(body.contains("hookRowDraw(View.class.getDeclaredMethod(\"draw\", Canvas.class));"));
        assertTrue(body.contains("if (id != 0) rememberRowId(id);"));
        assertTrue("installed with the factory hook",
                app.contains("installSharedBubbleFactoryHook();\n        observeRowsEarly();"));
        assertTrue("one hook per method", app.contains("if (!rowDrawHooks.add(method)) return;"));
    }

    @Test public void incompleteFingerprintNeverSharesATexture() {
        // Codex review on #138: a hardware-bitmap wallpaper is skipped by the software probe, so
        // two different wallpapers could fingerprint alike once identities are dropped.
        int[] pixels = new int[WallpaperCapture.PROBE_W * WallpaperCapture.PROBE_H];
        assertTrue(WallpaperCapture.lookKeyAllowed(pixels, true));
        assertFalse(WallpaperCapture.lookKeyAllowed(pixels, false));
        assertFalse(WallpaperCapture.lookKeyAllowed(null, true));
    }

    @Test public void provisionalTextureIsKeyedByLookNotIdentity() throws IOException {
        String source = read(SOURCE);
        assertTrue(source.contains("desiredLook=usable && capture.contentKey!=null ? capture.contentKey+\"|blur=\"+radius : null;"));
        assertTrue(source.contains("boolean memory=desiredLook.equals(lastLook) && lastTexture!=null;"));
        assertTrue("the chat's own render still replaces it",
                source.indexOf("texture=shared;") < source.indexOf("start();\n    }\n    public static float blurRadius"));
        String capture = read("src/main/java/com/waenhancer/theme/WallpaperCapture.java");
        assertTrue("no look key when a drawable could not be fingerprinted",
                capture.contains("if (lookKeyAllowed(pixels, complete[0])) plan.contentKey"));
        assertTrue(capture.contains("complete[0] = false;"));
        assertTrue("look = geometry + fingerprint, without drawable identities", capture.contains(
                "plan.contentKey = withContent(window.getWidth() + \"x\" + window.getHeight()"));
    }
}
