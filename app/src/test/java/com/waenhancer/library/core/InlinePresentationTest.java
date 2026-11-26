package com.waenhancer.library.core;

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
