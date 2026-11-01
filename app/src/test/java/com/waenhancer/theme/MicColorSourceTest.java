package com.waenhancer.theme;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

public class MicColorSourceTest {
    @Test public void tintListHasPriority() {
        assertEquals(Integer.valueOf(1), MicColorSource.select(1, 2, 3, 4));
    }

    @Test public void fallsThroughPublicSourcesInOrder() {
        assertEquals(Integer.valueOf(2), MicColorSource.select(null, 2, 3, 4));
        assertEquals(Integer.valueOf(3), MicColorSource.select(null, null, 3, 4));
        assertEquals(Integer.valueOf(4), MicColorSource.select(null, null, null, 4));
        assertNull(MicColorSource.select(null, null, null, null));
    }

    @Test public void unknownColourKeepsLastReferenceStartingAtProfileWhite() {
        ComposerPalette palette = new ComposerPalette();
        assertFalse(palette.update(MicColorSource.select(null, null, null, null)));
        assertEquals(ComposerPalette.CURRENT_PROFILE_ICON, palette.color());
        assertTrue(palette.update(MicColorSource.select(0xff112233, null, null, null)));
        assertFalse(palette.update(MicColorSource.select(null, null, null, null)));
        assertEquals(0xff112233, palette.color());
    }

    /** PorterDuffColorFilter.getColor() is not in the public SDK; calling it broke compilation (#122). */
    @Test public void mainSourcesNeverCallHiddenPorterDuffGetter() throws IOException {
        Pattern hidden = Pattern.compile("PorterDuffColorFilter\\)\\s*\\w+\\)\\s*\\.getColor\\(");
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String java = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    assertFalse(p.toString(), hidden.matcher(java).find());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
        }
        String panes = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/waenhancer/xposed/features/customization/ConversationGlassPanes.java")),
                StandardCharsets.UTF_8);
        assertTrue("BlendModeColorFilter is API 29+", panes.contains("Build.VERSION_CODES.Q"));
        assertTrue(panes.contains("MicColorSource.select(tintColor, blendColor, observedColor, drawableColor)"));
        assertFalse("no reflection on the hidden getter", panes.contains("callMethod(filter, \"getColor\")"));
    }
}
