package com.waenhancer.xposed.features.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GalleryAttachTest {
    @Test
    public void retriesAreBounded() {
        assertTrue(GalleryAttach.retry(0));
        assertTrue(GalleryAttach.retry(GalleryAttach.MAX_ATTEMPTS - 2));
        assertFalse(GalleryAttach.retry(GalleryAttach.MAX_ATTEMPTS - 1));
    }

    /** Items kept under the LID and under the phone JID of the same chat are both listed. */
    @Test
    public void bothFormsOfTheOpenChatArePassed() {
        assertArrayEquals(new String[]{"1@lid", "55@s.whatsapp.net"}, GalleryAttach.pair(null, "1@lid", "55@s.whatsapp.net"));
        assertArrayEquals(new String[]{"55@s.whatsapp.net", "1@lid"}, GalleryAttach.pair("55@s.whatsapp.net", "1@lid", "55@s.whatsapp.net"));
        assertArrayEquals(new String[]{"1@lid", null}, GalleryAttach.pair(null, "1@lid", "1@lid"));
    }

    /** The screen's own chat wins; another open conversation never adds its JID. */
    @Test
    public void anUnrelatedOpenConversationIsNotMixedIn() {
        assertArrayEquals(new String[]{"77@s.whatsapp.net", null}, GalleryAttach.pair("77@s.whatsapp.net", "1@lid", "55@s.whatsapp.net"));
        assertNull(GalleryAttach.pair(null, null, null));
    }
}
