package com.waenhancer.library.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.waenhancer.library.android.GalleryAccess;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InlinePresentationTest {

    private static final class FakeView implements InlinePresentation.Node {
        final String id;
        final boolean image;
        final int w, h;
        boolean visible = true;
        final List<FakeView> children = new ArrayList<>();

        FakeView(String id, boolean image, int w, int h, FakeView... kids) {
            this.id = id;
            this.image = image;
            this.w = w;
            this.h = h;
            children.addAll(Arrays.asList(kids));
        }

        @Override public String idName() { return id; }
        @Override public boolean isImageView() { return image; }
        @Override public boolean isVisible() { return visible; }
        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public List<FakeView> children() { return children; }
    }

    @Test
    public void presentsOnlyWhenTheNativeFileIsGoneAndTheCopyIsIntact() {
        assertTrue(InlinePresentation.shouldPresent(true, true, false, false));
        assertFalse("native media wins when WhatsApp still has it", InlinePresentation.shouldPresent(true, true, true, false));
        assertFalse(InlinePresentation.shouldPresent(false, true, false, false));
        assertFalse("missing/corrupt copies are not shown", InlinePresentation.shouldPresent(true, false, false, false));
        assertFalse("received view-once keeps native expiry", InlinePresentation.shouldPresent(true, true, false, true));
    }

    @Test
    public void viewOnceShowsTheKeptCopyWhetherOrNotWhatsAppStillHasTheFile() {
        assertTrue("view-once, file still there", InlinePresentation.presentKeptCopy(true, true, true));
        assertTrue("view-once, expired", InlinePresentation.presentKeptCopy(true, false, true));
        assertFalse("no copy, nothing to show", InlinePresentation.presentKeptCopy(true, true, false));
        assertFalse("normal media: native wins", InlinePresentation.presentKeptCopy(false, true, true));
        assertTrue("normal media whose file is gone", InlinePresentation.presentKeptCopy(false, false, true));
        assertFalse(InlinePresentation.presentKeptCopy(false, false, false));
        assertTrue(InlinePresentation.lookUpKeptCopy(true, true));
        assertTrue(InlinePresentation.lookUpKeptCopy(false, false));
        assertFalse("no provider call for normal media WhatsApp still has", InlinePresentation.lookUpKeptCopy(false, true));
    }

    @Test
    public void mediaBubbleSizeFollowsTheAspectRatioWithinClamps() {
        float d = 3.5f; // 260dp = 910px, 140dp = 490px, 340dp = 1190px
        assertArrayEquals(new int[] { 910, 683 }, InlinePresentation.mediaBubbleSize(4000, 3000, 1080, d));
        assertArrayEquals("square", new int[] { 910, 910 }, InlinePresentation.mediaBubbleSize(720, 720, 1080, d));
        assertArrayEquals("tall portrait is clamped", new int[] { 910, 1190 },
                InlinePresentation.mediaBubbleSize(1080, 2400, 1080, d));
        assertArrayEquals("panorama is clamped", new int[] { 910, 490 },
                InlinePresentation.mediaBubbleSize(4000, 1000, 1080, d));
        assertArrayEquals("narrow row caps the width", new int[] { 600, 800 },
                InlinePresentation.mediaBubbleSize(3000, 4000, 600, d));
        assertNull(InlinePresentation.mediaBubbleSize(0, 100, 1080, d));
        assertNull(InlinePresentation.mediaBubbleSize(100, -1, 1080, d));
        assertNull(InlinePresentation.mediaBubbleSize(100, 100, 0, d));
        assertNull(InlinePresentation.mediaBubbleSize(100, 100, 1080, 0f));
        assertNull(InlinePresentation.mediaBubbleSize(100, 100, 1080, Float.NaN));
    }

    @Test
    public void thumbnailsAreScaledToJustCoverTheLargestBubble() {
        assertEquals("landscape bubble 910x683: height decides by a hair", 683f / 3000f,
                InlinePresentation.coverScale(4000, 3000, 910, 683), 1e-6);
        assertEquals("portrait: the width must still cover the bubble", 910f / 1080f, InlinePresentation.coverScale(1080, 2400, 910, 1190), 1e-6);
        assertEquals("never upscaled", 1f, InlinePresentation.coverScale(400, 300, 910, 1190), 0f);
        assertEquals(1f, InlinePresentation.coverScale(0, 300, 910, 1190), 0f);
    }

    @Test
    public void aViewOnceBodyIsFoundBeforeItIsLaidOut() {
        // Re-presentation during the bind runs before WhatsApp measures a fresh row.
        FakeView body = new FakeView("view_once_media_container", false, 0, 0);
        FakeView row = new FakeView(null, false, 0, 0, new FakeView("main_layout", false, 0, 0, body));
        assertSame(body, InlinePresentation.selectHost(row, 150, true).node);
        FakeView hidden = new FakeView("view_once_media_container", false, 0, 0);
        hidden.visible = false;
        assertNull(InlinePresentation.selectHost(new FakeView(null, false, 0, 0, hidden), 150, true));
    }

    @Test
    public void viewOnceRowsPreferTheBodyOverAnyImageView() {
        FakeView icon = new FakeView("view_once_control_icon", true, 200, 200);
        FakeView body = new FakeView("view_once_media_container_large", false, 432, 162, icon);
        FakeView row = new FakeView(null, false, 1440, 220, new FakeView("main_layout", false, 500, 200, body));
        assertSame(body, InlinePresentation.selectHost(row, 150, true).node);
        assertEquals(InlinePresentation.HostKind.VIEW_ONCE_BODY, InlinePresentation.selectHost(row, 150, true).kind);
        assertSame("other rows keep the thumbnail-first order", icon, InlinePresentation.selectHost(row, 150, false).node);
        FakeView normal = new FakeView(null, false, 1440, 500, new FakeView("media_container", false, 600, 400,
                new FakeView("image", true, 600, 400)));
        assertEquals("a view-once flag without a body falls back", InlinePresentation.HostKind.THUMBNAIL,
                InlinePresentation.selectHost(normal, 150, true).kind);
    }

    @Test
    public void picksTheMediaThumbnailNotQuotesAvatarsOrTheRowItself() {
        FakeView thumb = new FakeView(null, true, 600, 400);
        FakeView quoteThumb = new FakeView(null, true, 900, 900);
        FakeView row = new FakeView("conversation_row_image", true, 1440, 700,
                new FakeView("main_layout", false, 1200, 650,
                        new FakeView("avatar", false, 120, 120, new FakeView(null, true, 120, 120)),
                        new FakeView("quoted_message_frame", false, 1000, 200,
                                new FakeView("quoted_thumb", false, 900, 900, quoteThumb)),
                        new FakeView("media_container", false, 600, 400, thumb,
                                new FakeView("control_frame", false, 96, 96, new FakeView(null, true, 96, 96)))));
        assertSame(thumb, InlinePresentation.selectThumbnail(row, 150));
    }

    @Test
    public void knownIdsWinOverLargerUnknownViewsAndHiddenViewsAreIgnored() {
        FakeView known = new FakeView("image", true, 300, 300);
        FakeView bigger = new FakeView(null, true, 800, 800);
        FakeView hidden = new FakeView("thumb", true, 900, 900);
        hidden.visible = false;
        FakeView row = new FakeView(null, false, 1440, 900, bigger, known, hidden);
        assertSame(known, InlinePresentation.selectThumbnail(row, 150));
    }

    @Test
    public void presentationDoesNotNeedANativeThumbnail() {
        // Expired view-once: WhatsApp shows only an icon and "Photo", no thumbnail view.
        FakeView body = new FakeView("view_once_media_container_large", false, 432, 162,
                new FakeView("view_once_control_icon", true, 48, 48),
                new FakeView("view_once_media_type_large", false, 200, 40));
        FakeView row = new FakeView(null, false, 1440, 220, new FakeView("main_layout", false, 500, 200, body,
                new FakeView("date", false, 80, 30)));
        InlinePresentation.Host host = InlinePresentation.selectHost(row, 150);
        assertSame(body, host.node);
        assertEquals(InlinePresentation.HostKind.VIEW_ONCE_BODY, host.kind);

        // Normal media whose thumbnail was never decoded: the media frame is used.
        FakeView frame = new FakeView("media_container", false, 600, 400, new FakeView("control_frame", false, 96, 96));
        InlinePresentation.Host fallback = InlinePresentation.selectHost(
                new FakeView(null, false, 1440, 500, new FakeView("main_layout", false, 640, 460, frame)), 150);
        assertSame(frame, fallback.node);
        assertEquals(InlinePresentation.HostKind.MEDIA_FRAME, fallback.kind);

        // A real thumbnail still wins.
        FakeView thumb = new FakeView("image", true, 600, 400);
        assertEquals(InlinePresentation.HostKind.THUMBNAIL, InlinePresentation.selectHost(
                new FakeView(null, false, 1440, 500, new FakeView("media_container", false, 600, 400, thumb)), 150).kind);
    }

    @Test
    public void hostSelectionNeverCoversTheWholeBubbleOrQuotedMedia() {
        FakeView row = new FakeView(null, false, 1440, 300, new FakeView("main_layout", false, 900, 280,
                new FakeView("quoted_thumb", false, 300, 300, new FakeView("view_once_media_container_large", false, 300, 120)),
                new FakeView("message_text", false, 800, 100)));
        assertNull(InlinePresentation.selectHost(row, 150));
    }

    @Test
    public void rowWithoutAMediaViewStaysNative() {
        FakeView row = new FakeView(null, false, 1440, 200,
                new FakeView("message_text", false, 1000, 100), new FakeView(null, true, 48, 48));
        assertNull(InlinePresentation.selectThumbnail(row, 150));
        assertNull(InlinePresentation.selectThumbnail(null, 150));
    }

    @Test
    public void galleryTokensAreSingleUseScopedAndExpire() {
        long now = 1_000_000L;
        GalleryAccess.Grant grant = new GalleryAccess.Grant("com.whatsapp", "me@s.whatsapp.net",
                "a@s.whatsapp.net", null, "Alice", null, now);
        String token = GalleryAccess.issue(grant);
        assertEquals(48, token.length());
        assertSame(grant, GalleryAccess.redeem(token, now + 10));
        assertNull("single use", GalleryAccess.redeem(token, now + 20));

        String late = GalleryAccess.issue(new GalleryAccess.Grant("com.whatsapp", "me", "a", null, null, null, now));
        assertNull("expired", GalleryAccess.redeem(late, now + GalleryAccess.TTL_MS + 1));
        assertNull(GalleryAccess.redeem("guess", now));
        assertNull(GalleryAccess.redeem(null, now));
    }
}
