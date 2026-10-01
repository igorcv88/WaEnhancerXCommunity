package com.waenhancer.theme;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlassWindowOrderTest {
    @Test public void popupOverDialogIncludesActivityDialogAndOwnButNoHigherWindows() {
        Object app = new Object(), baseWindow = new Object(), dialogWindow = new Object();
        var base = new GlassWindowOrder.Entry<>("base", app, baseWindow, 1, true);
        var dialog = new GlassWindowOrder.Entry<>("dialog", app, dialogWindow, 2, true);
        var popup = new GlassWindowOrder.Entry<>("popup", dialogWindow, new Object(), 1000, true);
        var later = new GlassWindowOrder.Entry<>("later", app, new Object(), 2, true);
        assertEquals(List.of("base", "dialog", "popup"), GlassWindowOrder.through(List.of(dialog, popup, base, later), "popup"));
        assertEquals(List.of("base", "dialog"), GlassWindowOrder.through(List.of(dialog, popup, base, later), "dialog"));
    }
    @Test public void unrelatedAndHiddenWindowsAreOmitted() {
        Object app = new Object();
        var unrelated = new GlassWindowOrder.Entry<>("other", new Object(), new Object(), 1, true);
        var base = new GlassWindowOrder.Entry<>("base", app, new Object(), 1, true);
        var hidden = new GlassWindowOrder.Entry<>("hidden", app, new Object(), 2, false);
        var own = new GlassWindowOrder.Entry<>("own", app, new Object(), 2, true);
        assertEquals(List.of("base", "own"), GlassWindowOrder.through(List.of(unrelated, base, hidden, own), "own"));
    }
    @Test public void unresolvableParentDoesNotInventAStack() {
        var popup = new GlassWindowOrder.Entry<>("popup", new Object(), new Object(), 1000, true);
        assertEquals(List.of("popup"), GlassWindowOrder.through(List.of(popup), "popup"));
    }
    @Test public void cyclicWindowTokensTerminateAndFailClosed() {
        Object one = new Object(), two = new Object();
        var a = new GlassWindowOrder.Entry<>("a", one, two, 1000, true);
        var b = new GlassWindowOrder.Entry<>("b", two, one, 1000, true);
        assertEquals(List.of("a"), GlassWindowOrder.through(List.of(a, b), "a"));
    }
}
