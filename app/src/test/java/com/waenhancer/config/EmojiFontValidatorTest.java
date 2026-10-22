package com.waenhancer.config;

import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class EmojiFontValidatorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File makeFont(boolean includeColor, boolean badOffset) throws Exception {
        File file = temp.newFile("sample.ttf");
        String[] tags = includeColor
                ? new String[]{"head", "maxp", "cmap", "CBDT", "CBLC"}
                : new String[]{"head", "maxp", "cmap"};
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.writeInt(0x00010000);
            raf.writeShort(tags.length);
            raf.write(new byte[6]);
            for (int i = 0; i < tags.length; i++) {
                raf.writeBytes(tags[i]);
                raf.writeInt(0);
                raf.writeInt(badOffset && i == 0 ? 900 : 12 + tags.length * 16);
                raf.writeInt(4);
            }
            raf.writeInt(0);
        }
        return file;
    }

    @Test public void allowsBoundedColorSfntDirectory() throws Exception {
        EmojiFontValidator.validate(makeFont(true, false));
    }

    @Test public void rejectsOrdinaryTextFont() throws Exception {
        assertThrows(IOException.class, () -> EmojiFontValidator.validate(makeFont(false, false)));
    }

    @Test public void rejectsTableOutsideFile() throws Exception {
        assertThrows(IOException.class, () -> EmojiFontValidator.validate(makeFont(true, true)));
    }

    @Test public void rejectsNonSfnt() throws Exception {
        File file = temp.newFile("not-a-font.ttf");
        assertThrows(IOException.class, () -> EmojiFontValidator.validate(file));
    }

    @Test public void validatesOptionalRealWorldSample() throws Exception {
        String sample = System.getenv("WAEX_TEST_EMOJI_FONT");
        if (sample != null && !sample.isEmpty()) {
            EmojiFontValidator.validate(new File(sample));
        }
    }
}
