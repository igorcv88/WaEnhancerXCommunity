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

    // Third review: as bright as the chrome rim, on a narrower Gaussian (same visible width).
    @Test public void bubbleRimIsAsBrightAsTheChromeRimButNarrower() throws IOException {
        assertEquals(1f, NativeBubbleGlass.BUBBLE_RIM_GAIN, 0f);
        assertTrue(NativeBubbleGlass.BUBBLE_HIGHLIGHT_GAIN >= 0.75f && NativeBubbleGlass.BUBBLE_HIGHLIGHT_GAIN < 1f);
        assertTrue(NativeBubbleGlass.BUBBLE_STROKE_DP < 1.0f && NativeBubbleGlass.BUBBLE_STROKE_DP >= 0.8f);
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
                text.contains("if (state != null && current == state[1]) {\n                    text.setTextColor(state[0]);"));
        assertTrue("every return to WhatsApp's colour is logged", text.contains("BubbleGlassTelemetry.contrastNative(text,"));
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

    @Test public void contrastIsHeldAtItsSetters() throws IOException {
        // Device video 2026-11-03: reply text and the play button flickered white/grey on every
        // scroll or tap, or stayed grey until the next touch. A WhatsApp colour reset invalidates
        // only the child, so no row recording (and no sync) followed it.
        String app = read(APP);
        int install = app.indexOf("private void installContrastGuards()");
        String guards = app.substring(install, app.indexOf("private Surface surfaceFor(", install));
        assertTrue(guards.contains("hookAllMethods(TextView.class, \"setTextColor\", guard)"));
        assertTrue(guards.contains("hookAllMethods(ImageView.class, \"setImageTintList\", guard)"));
        assertTrue(guards.contains("hookAllMethods(View.class, \"setLayerType\", guard)"));
        assertTrue("UI thread only, and never answering this module's own writes",
                guards.contains("if (applyingContrast || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()"));
        assertTrue(app.contains("installTintObserver();\n        installContrastGuards();"));
        int start = app.indexOf("void reassertContrast(View view, String method)");
        String body = app.substring(start, app.indexOf("private void syncContrast(View row)", start));
        assertTrue("WhatsApp's new colour becomes the one restored later",
                body.contains("state[0] = text.getTextColors();\n                    text.setTextColor(state[1]);"));
        assertTrue(body.contains("state[0] = icon.getImageTintList();\n                    icon.setImageTintList(state[1]);"));
        assertTrue(body.contains("liftedLayers.put(view, view.getLayerType());"));
        assertTrue("already applied: no write, no invalidation loop",
                body.contains("if (state == null || text.getTextColors() == state[1]) return;"));
        // Ownership follows the held state; restoring writes under the guard.
        int text = app.indexOf("private void tintText(");
        String tint = app.substring(text, app.indexOf("private void tintIcon(", text));
        assertTrue(tint.contains("if (quoteTexts.remove(text) != null) contrastOwners.remove(text);"));
        assertTrue(tint.contains("contrastOwners.put(text, this);\n            text.setTextColor(wanted);"));
        int restore = app.indexOf("private void restoreReplyTexts()");
        String all = app.substring(restore, app.indexOf("private void restoreContrast()", restore));
        assertTrue(all.contains("applyingContrast = true;") && all.contains("contrastOwners.values().removeIf(owner -> owner == this);"));
        int sync = app.indexOf("private void syncReplyText(View row)");
        assertTrue(app.substring(sync, app.indexOf("void reassertContrast(", sync))
                .contains("try { syncContrast(row); } finally { applyingContrast = outer; }"));
    }

    @Test public void contrastHasHysteresisAndFollowsTheGlassRecording() throws IOException {
        // Device trace 2026-11-03 after #145: reply text and play button still flickered while
        // WhatsApp setter resets were rare (reasserted_text=4, reasserted_icon=0): the colours
        // followed every single native recording and every session scan landing in one.
        String app = read(APP);
        int hold = app.indexOf("private boolean contrastGlass(View row, GlassSpec material)");
        String body = app.substring(hold, app.indexOf("private void syncContrast(View row)", hold));
        assertTrue(body.contains("if (bubbleGlass.hasGlass(row)) { nativeSince.remove(row); return true; }"));
        assertTrue("only rows that had glass colours are held",
                body.contains("if (!rowsWithGlassContrast.contains(row)) return false;"));
        assertTrue("a real native transition still restores after the hold",
                body.contains("row.postDelayed(() -> { holdPending.remove(row); if (!closed) syncReplyText(row); }, left + 16);"));
        assertTrue("short enough that a real native row recolours promptly",
                app.contains("static final long CONTRAST_HOLD_MS = 500;"));
        int sync = app.indexOf("private void syncContrast(View row)");
        String contrast = app.substring(sync, app.indexOf("private void clearFill(", sync));
        assertTrue(contrast.contains("boolean glass = contrastGlass(row, material);"));
        assertTrue("no layer change inside a recording",
                contrast.contains("if (visualizer != null && !contrastInDraw) liftLayer(visualizer, glass);"));
        assertTrue(contrast.contains("if (!contrastInDraw) {\n                    clearFill(group.getBackground(), glass);"));
        assertTrue("tints applied in the glass recording itself, the posted sync still follows",
                app.contains("if (glassNow != null && glassNow.hasGlass(row)) syncTintsInDraw(row);\n"
                        + "                    if (contrastPending.add(row)) row.post("));
        int restore = app.indexOf("private void restoreReplyTexts()");
        String all = app.substring(restore, app.indexOf("private void restoreContrast()", restore));
        assertTrue(all.contains("rowsWithGlassContrast.clear();") && all.contains("nativeSince.clear();"));
    }

    @Test public void replacedContentViewsGetTheGlassColourBeforeTheyDraw() throws IOException {
        // Owner trace 2026-11-04: contrast_held=0 contrast_restored=0 contrast_fresh=797 per
        // ~1230 glass recordings, white while scrolling and grey at rest: WhatsApp replaces the
        // reply/voice views, and a replaced view at rest is recorded by its own sub-tree only.
        String app = read(APP);
        int install = app.indexOf("private void installContrastGuards()");
        String guards = app.substring(install, app.indexOf("private Surface surfaceFor(", install));
        assertTrue(guards.contains("XposedBridge.hookAllMethods(TextView.class, \"onDraw\", atDraw);"));
        assertTrue(guards.contains("XposedBridge.hookAllMethods(ImageView.class, \"onDraw\", atDraw);"));
        assertTrue("before the draw, so this recording already uses the colour",
                guards.contains("@Override protected void beforeHookedMethod(MethodHookParam param) {\n                if (applyingContrast || sessions.isEmpty()) return;"));
        assertTrue("cheap id gate before any lookup", guards.indexOf("int which = drawWatchIndex(view, id);")
                < guards.indexOf("session.glassContrastRow(view)"));
        assertTrue(app.contains("static final String[] DRAW_WATCH = {\"quoted_text\", \"description\", \"date\", \"call_log_subtitle\", \"control_btn\", \"call_type_icon\","));
        int enforce = app.indexOf("void enforceAtDraw(View row, View view, String name)");
        String body = app.substring(enforce, app.indexOf("private void syncContrast(View row)", enforce));
        assertTrue("already white: no write", body.contains("if (state != null && ((TextView) view).getTextColors() == state[1]) return;"));
        assertTrue("regular timestamps outside voice rows keep WhatsApp's colour",
                body.contains("case \"date\": if (!voice && row.getId() != dateWrapperId) return;"));
        assertTrue(body.contains("applyingContrast = true;") && body.contains("BubbleGlassTelemetry.contrast(4);"));
        int row = app.indexOf("View glassContrastRow(View view)");
        assertTrue("only rows whose colours are currently glass",
                app.substring(row, enforce).contains("if (rowsWithGlassContrast.contains(parent)) return (View) parent;"));
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
                && fill.contains("flat.setColor(original);"));
        assertTrue("Codex review on #144: the full colour, never an effective alpha",
                fill.contains("clearedFills.put(fill, flat.getColor());") && !fill.contains("setAlpha("));
        int restore = app.indexOf("private void restoreReplyTexts()");
        String all = app.substring(restore, app.indexOf("private void restoreReplyCorners()", restore));
        assertTrue(all.contains("((android.graphics.drawable.ColorDrawable) entry.getKey()).setColor(entry.getValue());"));
    }

    @Test public void detachedStickerTimesUseOnlyTheirNativeBackgroundFactory() throws IOException {
        String app = read(APP);
        assertTrue("the date factory is separate from the regular bubble factory",
                app.contains("installSharedBubbleDateFactoryHook();"));
        int start = app.indexOf("private void installSharedBubbleDateFactoryHook()");
        String registration = app.substring(start, app.indexOf("private View detachedDateOwner(", start));
        assertTrue(registration.contains("Unobfuscator.loadBallonDateDrawable(classLoader)"));
        assertTrue("keeps the native drawable result", !registration.contains("param.setResult("));
        assertTrue(registration.contains("dateStateSides.put(state, previous == null ? direction"));
        int owner = app.indexOf("private View detachedDateOwner(Drawable drawable)");
        String scope = app.substring(owner, app.indexOf("private boolean drawViewOnceBackground(", owner));
        assertTrue("only a date_wrapper's own background is eligible",
                scope.contains("owner.getId() != dateWrapperId || owner.getBackground() != drawable"));
        assertTrue("only inside a verified conversation row", scope.contains("if (messageRow(ancestor)) return owner;"));
        assertTrue("the renderer uses the same session and wallpaper source",
                scope.contains("session.drawBubble(drawable, canvas, direction == 3)"));
        assertTrue("draw context is always restored", scope.contains("GlassMaterialDrawable.leaveRowDraw(previous);"));
        assertTrue("the detached timestamp and delivery glyph use the same glass foreground",
                app.contains("if (row.getId() == dateWrapperId) {")
                        && app.contains("tintText(rowChild(row, \"date\"), glass, secondary);"));
    }

    @Test public void viewOncePillHasItsOwnScopedNativeDrawableAndSameFrameColours() throws IOException {
        String app = read(APP);
        int start = app.indexOf("private boolean drawViewOnceBackground(");
        String body = app.substring(start, app.indexOf("private void hookPendingBubbleClasses()", start));
        int ownerGateStart = app.indexOf("private View viewOnceBackgroundOwner(");
        String ownerGate = app.substring(ownerGateStart, start);
        assertTrue("background callback identifies the container drawing this nine-patch",
                ownerGate.contains("drawable.getCallback() instanceof View")
                        && ownerGate.contains("owner.getBackground() != drawable && owner.getForeground() != drawable"));
        assertTrue("verified container has a scoped host while its own RenderNode is recorded",
                body.contains("GlassMaterialDrawable.enterRowDraw(owner, canvas)")
                        && body.contains("GlassMaterialDrawable.leaveRowDraw(previous)"));
        assertTrue("the verified host ID is resolved once from native resources",
                app.contains("getIdentifier(\"view_once_media_container_large\", \"id\", pkg)"));
        assertTrue(body.contains("owner.getId() != viewOnceLargeId && owner.getId() != viewOnceSmallId"));
        assertTrue("verified native row and side: ambiguous alignments stay native",
                body.contains("if (!messageRow(row) || row.getWidth() <= 0) return false;")
                        && body.contains("if (Math.abs(leftGap - rightGap) < row.getWidth() / 4) return false;"));
        assertTrue(body.contains("if (!sharedFactoryWanted(outgoing)) return false;"));
        assertTrue(body.contains("session.drawBubble(drawable, canvas, outgoing)"));
        int guards = app.indexOf("private void installContrastGuards()");
        String guard = app.substring(guards, app.indexOf("XC_MethodHook atDraw =", guards));
        assertTrue("the opaque download fill is handled before View.draw paints its background",
                guard.contains("View.class.getDeclaredMethod(\"draw\", Canvas.class)"));
        assertTrue(guard.contains("session.prepareViewOnceFillAtDraw(view)"));
        assertTrue("replacement labels and icons are retinted before their own draw",
                app.contains("\"view_once_media_type_large\", \"view_once_media_type\", \"view_once_control_icon\""));
        int enforce = app.indexOf("void enforceAtDraw(View row, View view, String name)");
        String tint = app.substring(enforce, app.indexOf("private void syncContrast(View row)", enforce));
        assertTrue(tint.contains("case \"view_once_media_type_large\": case \"view_once_media_type\":"));
        assertTrue(tint.contains("case \"view_once_control_icon\":"));
        assertTrue("the latest native fill remains restorable after a download state update",
                app.contains("clearedFills.put(fill, flat.getColor());\n            } else if (flat.getColor() != 0) {"));
    }

    @Test public void conversationTypingAndRecordingStatusFollowsHeaderAndRestores() throws IOException {
        String pane = read("src/main/java/com/waenhancer/xposed/features/customization/ConversationGlassPanes.java");
        String app = read(APP);
        assertTrue("the contact status is distinct from the title",
                pane.contains("findById(toolbar, \"conversation_contact_status\")"));
        assertTrue("status changes are recoloured before the native TextEmojiLabel draws",
                app.contains("conversation.toneConversationStatusAtDraw(view); return;"));
        assertTrue("the native title owns the status contrast",
                pane.contains("int foreground = title.getCurrentTextColor();"));
        assertTrue("the compound typing/recording icon is tinted when exposed by WhatsApp",
                pane.contains("status.setCompoundDrawableTintList(tint);"));
        assertTrue("native text and compound tint are restored when header glass is released",
                pane.contains("status.setTextColor(screen.headerStatusTexts.get(status));")
                        && pane.contains("status.setCompoundDrawableTintList(screen.headerStatusCompounds.get(status));"));
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
                app.contains("installSharedBubbleFactoryHook();\n        installSharedBubbleDateFactoryHook();\n        observeRowsEarly();"));
        assertTrue("one hook per method", app.contains("if (!rowDrawHooks.add(method)) return;"));
    }

    // Owner choice 2026-11-03, on renders of the owner's own colours checked against the
    // device: tinted glass. The blur is filtered by the chosen colour in linear light by a user
    // amount; the wash never changes, so nothing white or grey is added (the Oklab move read as a
    // pastel, whitish cast on device).
    @Test public void bubbleColorsFilterTheBlurWithoutChangingTheWash() {
        GlassSpec base = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF25D366, 10f, true, false);
        GlassSpec tinted = base.withBubbleTint(0xFF0A2D49);
        try {
            KyantLens.publishBubbleColourAmount(0.7f);
            KyantLens.Params plain = KyantLens.appSurface(base, 120, 3f, 46);
            KyantLens.Params colored = KyantLens.appSurface(tinted, 120, 3f, 46);
            assertEquals("same wash alpha as untinted glass", plain.tint >>> 24, colored.tint >>> 24);
            float[] filter = KyantLens.glassFilter(0x0A2D49);
            assertEquals(filter[0], colored.filterR, 0f);
            assertEquals(filter[1], colored.filterG, 0f);
            assertEquals(filter[2], colored.filterB, 0f);
            assertEquals(0.7f, colored.hueAmount, 0f);
            assertEquals("untinted surfaces get no filter", 0f, plain.hueAmount, 0f);
            assertNotEquals("a colour change rebuilds the effect", plain.key(), colored.key());
            KyantLens.publishBubbleColourAmount(0f);
            assertEquals("0% is plain glass", 0f, KyantLens.appSurface(tinted, 120, 3f, 46).hueAmount, 0f);
        } finally {
            KyantLens.publishBubbleColourAmount(0.7f);
        }
        assertTrue("multiplies the transmitted light; adds nothing",
                KyantLens.SHADER.contains("tgLin *= mix(float3(1.0), uHue.rgb, uHue.a);"));
        assertFalse(KyantLens.SHADER.contains("okA"));
    }

    @Test public void glassFilterIsTheColourAtFullBrightness() {
        float[] navy = KyantLens.glassFilter(0x0A2D49);
        assertEquals("brightest channel passes fully", 1f, navy[2], 1e-6f);
        assertTrue(navy[0] < navy[1] && navy[1] < navy[2]);
        float[] grey = KyantLens.glassFilter(0x404040);
        assertEquals(1f, grey[0], 1e-6f); assertEquals(1f, grey[1], 1e-6f); assertEquals(1f, grey[2], 1e-6f);
        float[] black = KyantLens.glassFilter(0x000000);
        assertEquals("black filters nothing instead of blacking the glass out", 1f, black[0], 0f);
    }

    // Device photo 2026-11-03: a light Bubble Color made the tinted spec's content colour dark,
    // which flipped the wash to white under the (base material's) white text.
    @Test public void bubbleWashFollowsTheUntintedMaterial() throws IOException {
        GlassSpec base = GlassSpec.resolve(GlassSpec.Variant.LIQUID, true, 0, 0xFF25D366, 10f, true, false);
        GlassSpec light = base.withBubbleTint(0xFF9ADCF0);
        assertNotEquals("the flip exists in the tinted spec", KyantLens.neutralWash(base), KyantLens.neutralWash(light));
        String glass = read(GLASS);
        assertTrue(glass.contains("KyantLens.neutralWash(base), density);"));
        assertTrue(glass.contains("params.tint = (params.tint & 0xFF000000) | washRgb;"));
        assertTrue(read(APP).contains("return bubbleGlass.draw(bubble, canvas, bubbleDrawMaterial(outgoing, base), base);"));
    }

    @Test public void bubbleColourAmountRebuildsRecordedRows() throws IOException {
        assertTrue(read(GLASS).contains("KyantLens.rimIntensity() + \":\" + KyantLens.bubbleColourAmount()"));
        assertTrue(read(APP).contains("KyantLens.publishBubbleColourAmount(LiquidGlassSettings.bubbleColourAmount(prefs));"));
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
