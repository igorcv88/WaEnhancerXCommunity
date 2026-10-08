package com.waenhancer.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureContentTest {
    private static final class Node {
        final Node parent;
        Node(Node parent) { this.parent = parent; }
    }
    @Test public void recursiveCoordinatorIsStillRefusedDespiteSafeWallpaper() {
        Node coordinator = new Node(null), pane = new Node(coordinator);
        String refusal = GlassPaneGraph.refusal(node -> node.parent, pane, coordinator,
                java.util.Collections.singletonList(new GlassPaneGraph.Pane<>(pane, coordinator)));
        assertNotNull(refusal);
        CaptureContent content = CaptureContent.classify(refusal == null, true);
        assertEquals(CaptureContent.CAPTURE_WALLPAPER_ONLY, content);
        assertFalse(content.completesRecovery(true));
    }
    @Test public void wallpaperDoesNotRecoverAMessageHeader() {
        CaptureContent content = CaptureContent.classify(false, true);
        assertEquals(CaptureContent.CAPTURE_WALLPAPER_ONLY, content);
        assertFalse(content.completesRecovery(true));
        assertTrue(content.completesRecovery(false));
    }
    @Test public void unavailableContentNeverCompletesRecovery() {
        CaptureContent content = CaptureContent.classify(false, false);
        assertEquals(CaptureContent.SOURCE_UNAVAILABLE, content);
        assertFalse(content.completesRecovery(false));
        assertFalse(content.completesRecovery(true));
    }
    @Test public void lateSafeMessageSourceCompletesRecoveryInSameEpoch() {
        SurfaceRecovery recovery = new SurfaceRecovery();
        recovery.failed(0, false); recovery.failed(16, false); recovery.failed(80, false);
        assertFalse(recovery.mayCapture(1079));
        assertTrue(recovery.mayCapture(1080));
        assertTrue(CaptureContent.classify(true, true).completesRecovery(true));
        recovery.ready();
        assertEquals(SurfaceRecovery.State.LIVE, recovery.state());
        assertEquals(0, recovery.generation());
    }
}
