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
    @Test public void firstOpaqueDiscColourWins() {
        // transparent container, translucent ripple, then the white disc fill
        assertEquals(Integer.valueOf(0xffffffff),
                MicColorSource.selectDisc(java.util.Arrays.asList(null, 0x00000000, 0x33ffffff, 0xffffffff, 0xff000000)));
    }

    @Test public void halfOpaqueCounts() {
        assertEquals(Integer.valueOf(0x80123456), MicColorSource.selectDisc(java.util.Arrays.asList(0x7fffffff, 0x80123456)));
    }

    @Test public void noDiscColourIsUnknown() {
        assertNull(MicColorSource.selectDisc(java.util.Arrays.asList(null, 0x00ffffff)));
        assertNull(MicColorSource.selectDisc(null));
    }

    @Test public void unknownColourKeepsLastReferenceStartingAtProfileWhite() {
        ComposerPalette palette = new ComposerPalette();
        assertFalse(palette.update(MicColorSource.selectDisc(java.util.Collections.singletonList(null))));
        assertEquals(ComposerPalette.CURRENT_PROFILE_ICON, palette.color());
        assertTrue(palette.update(MicColorSource.selectDisc(java.util.Collections.singletonList(0xff112233))));
        assertFalse(palette.update(MicColorSource.selectDisc(java.util.Collections.singletonList(null))));
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
        assertTrue("reference is the action button disc", panes.contains("MicColorSource.selectDisc(colors)"));
        assertTrue(panes.contains("conversation_entry_action_button"));
        assertFalse("no reflection on the hidden getter", panes.contains("callMethod(filter, \"getColor\")"));
    }
}
