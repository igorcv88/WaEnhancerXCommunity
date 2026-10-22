package com.waenhancer.config;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/** Checks the SFNT directory before handing an untrusted font to Android's font parser. */
public final class EmojiFontValidator {
    public static final long MAX_BYTES = 128L * 1024L * 1024L;

    private EmojiFontValidator() {}

    public static void validate(File file) throws IOException {
        long length = file.length();
        if (length < 28 || length > MAX_BYTES) throw new IOException("Invalid font size");

        try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
            long signature = Integer.toUnsignedLong(source.readInt());
            // sfnt TrueType and OpenType; WOFF, TTC and Apple dfont containers are not TTFs.
            if (signature != 0x00010000L && signature != 0x4f54544fL && signature != 0x74727565L) {
                throw new IOException("Expected an OpenType or TrueType .ttf/.otf font");
            }
            int tableCount = source.readUnsignedShort();
            if (tableCount == 0 || tableCount > 128 || 12L + 16L * tableCount > length) {
                throw new IOException("Invalid SFNT table directory");
            }
            source.skipBytes(6);
            Set<String> tables = new HashSet<>();
            for (int i = 0; i < tableCount; i++) {
                byte[] tag = new byte[4];
                source.readFully(tag);
                source.skipBytes(4); // checksum
                long offset = Integer.toUnsignedLong(source.readInt());
                long size = Integer.toUnsignedLong(source.readInt());
                if (offset > length || size > length - offset) {
                    throw new IOException("Font table exceeds file bounds");
                }
                tables.add(new String(tag, java.nio.charset.StandardCharsets.US_ASCII));
            }
            if (!tables.contains("cmap") || !tables.contains("head") || !tables.contains("maxp")) {
                throw new IOException("Missing required font tables");
            }
            boolean bitmap = tables.contains("CBDT") && tables.contains("CBLC");
            boolean colorVector = tables.contains("COLR") && tables.contains("CPAL");
            if (!bitmap && !colorVector && !tables.contains("sbix")) {
                throw new IOException("This file has no recognized color emoji tables");
            }
        }
    }
}
