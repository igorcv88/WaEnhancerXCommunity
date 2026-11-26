package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Structural validation of a complete photo or video file, before it is accepted.
 *
 * <p>Content sniffing ({@link MediaSniffer}) only looks at the first bytes; a truncated download
 * or a partially written file passes it. This validator walks the whole container and rejects
 * anything incomplete or inconsistent, so the library never stores a "kept" copy that cannot be
 * shown later. Decodability itself is checked additionally on the device by
 * {@link MediaLibrary.DecodeProbe} (images: bounds decode; videos: duration and a video track).</p>
 *
 * <ul>
 *   <li>JPEG: SOI, a walkable marker sequence up to SOS, and an EOI at the end of the file.</li>
 *   <li>PNG: signature, IHDR first, every chunk's CRC-32, and IEND as the last chunk.</li>
 *   <li>GIF: header and the 0x3B trailer.</li>
 *   <li>WebP: the RIFF size equals the file size.</li>
 *   <li>ISO base media (MP4, 3GP, QuickTime, HEIC, AVIF): top-level boxes tile the file exactly;
 *   {@code ftyp} first; videos need {@code moov} (or fragments) and media data; still images need
 *   {@code meta}.</li>
 *   <li>WebM/Matroska: EBML header and a Segment element (sizes checked when declared).</li>
 * </ul>
 */
public final class MediaValidator {
    private MediaValidator() { }

    /** Returns {@code null} when valid, otherwise a short machine-readable reason. */
    public static String validate(File file, String mime) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) return "empty";
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (mime == null) return "unknown_type";
            switch (mime) {
                case "image/jpeg": return jpeg(in);
                case "image/png": return png(in);
                case "image/gif": return gif(in);
                case "image/webp": return webp(in);
                case "video/webm": return matroska(in);
                case "image/heic":
                case "image/avif": return isoBmff(in, false);
                case "video/mp4":
                case "video/3gpp":
                case "video/quicktime": return isoBmff(in, true);
                default: return "unsupported_type";
            }
        }
    }

    private static String jpeg(RandomAccessFile in) throws IOException {
        long length = in.length();
        if (length < 4) return "jpeg_truncated";
        in.seek(0);
        if (in.readUnsignedByte() != 0xFF || in.readUnsignedByte() != 0xD8) return "jpeg_no_soi";
        long position = 2;
        boolean sawFrame = false;
        while (true) {
            if (position + 4 > length) return "jpeg_truncated";
            in.seek(position);
            if (in.readUnsignedByte() != 0xFF) return "jpeg_bad_marker";
            int marker = in.readUnsignedByte();
            while (marker == 0xFF) { // fill bytes
                position++;
                marker = in.readUnsignedByte();
            }
            if (marker == 0xD9) return "jpeg_no_scan";
            if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) { // standalone
                position += 2;
                continue;
            }
            int segment = in.readUnsignedShort();
            if (segment < 2 || position + 2 + segment > length) return "jpeg_truncated";
            if ((marker >= 0xC0 && marker <= 0xC3) || (marker >= 0xC5 && marker <= 0xC7)
                    || (marker >= 0xC9 && marker <= 0xCB) || (marker >= 0xCD && marker <= 0xCF)) {
                sawFrame = true;
            }
            if (marker == 0xDA) break; // start of scan: entropy-coded data follows
            position += 2 + segment;
        }
        if (!sawFrame) return "jpeg_no_frame";
        // The image must end with EOI; tolerate trailing zero padding some encoders add.
        long end = length - 1;
        in.seek(end);
        while (end > 0 && in.readUnsignedByte() == 0x00) {
            end--;
            in.seek(end);
        }
        if (end < 1) return "jpeg_truncated";
        in.seek(end - 1);
        if (in.readUnsignedByte() != 0xFF || in.readUnsignedByte() != 0xD9) return "jpeg_no_eoi";
        return null;
    }

    private static String png(RandomAccessFile in) throws IOException {
        long length = in.length();
        if (length < 8 + 25 + 12) return "png_truncated";
        in.seek(8);
        long position = 8;
        boolean first = true;
        byte[] buffer = new byte[64 * 1024];
        while (position < length) {
            if (position + 12 > length) return "png_truncated";
            in.seek(position);
            long size = in.readInt() & 0xFFFFFFFFL;
            byte[] type = new byte[4];
            in.readFully(type);
            String name = new String(type, StandardCharsets.US_ASCII);
            if (first && !"IHDR".equals(name)) return "png_no_ihdr";
            first = false;
            if (position + 12 + size > length) return "png_truncated";
            CRC32 crc = new CRC32();
            crc.update(type);
            long remaining = size;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) return "png_truncated";
                crc.update(buffer, 0, read);
                remaining -= read;
            }
            long stored = in.readInt() & 0xFFFFFFFFL;
            if (stored != crc.getValue()) return "png_bad_crc";
            position += 12 + size;
            if ("IEND".equals(name)) return position == length ? null : "png_data_after_iend";
        }
        return "png_no_iend";
    }

    private static String gif(RandomAccessFile in) throws IOException {
        if (in.length() < 14) return "gif_truncated";
        in.seek(in.length() - 1);
        return in.readUnsignedByte() == 0x3B ? null : "gif_no_trailer";
    }

    private static String webp(RandomAccessFile in) throws IOException {
        if (in.length() < 20) return "webp_truncated";
        in.seek(4);
        long declared = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
        return declared + 8 == in.length() ? null : "webp_size_mismatch";
    }

    private static String isoBmff(RandomAccessFile in, boolean video) throws IOException {
        long length = in.length();
        long position = 0;
        boolean ftyp = false;
        boolean moov = false;
        boolean media = false;
        boolean meta = false;
        int boxes = 0;
        while (position < length) {
            if (position + 8 > length) return "iso_truncated";
            in.seek(position);
            long size = in.readInt() & 0xFFFFFFFFL;
            byte[] type = new byte[4];
            in.readFully(type);
            String name = new String(type, StandardCharsets.ISO_8859_1);
            long header = 8;
            if (size == 1) {
                if (position + 16 > length) return "iso_truncated";
                size = in.readLong();
                header = 16;
            } else if (size == 0) {
                size = length - position; // box extends to end of file
            }
            if (size < header || position + size > length) return "iso_truncated";
            if (boxes == 0 && !"ftyp".equals(name)) return "iso_no_ftyp";
            switch (name) {
                case "ftyp": ftyp = true; break;
                case "moov": moov = true; break;
                case "moof": moov = true; break;
                case "mdat": media = true; break;
                case "meta": meta = true; break;
                default: break;
            }
            boxes++;
            position += size;
        }
        if (!ftyp) return "iso_no_ftyp";
        if (video) {
            if (!moov) return "iso_no_moov";
            if (!media) return "iso_no_mdat";
        } else if (!meta) {
            return "iso_no_meta";
        }
        return null;
    }

    private static String matroska(RandomAccessFile in) throws IOException {
        long length = in.length();
        if (length < 16) return "mkv_truncated";
        in.seek(0);
        if (in.readInt() != 0x1A45DFA3) return "mkv_no_ebml";
        long headerSize = vint(in);
        if (headerSize < 0) return "mkv_bad_size";
        long segmentAt = in.getFilePointer() + headerSize;
        if (segmentAt + 5 > length) return "mkv_truncated";
        in.seek(segmentAt);
        if (in.readInt() != 0x18538067) return "mkv_no_segment";
        long segmentSize = vint(in);
        if (segmentSize == -2) return null; // "unknown size" (live recording): accepted
        if (segmentSize < 0) return "mkv_bad_size";
        return in.getFilePointer() + segmentSize <= length ? null : "mkv_truncated";
    }

    /** EBML variable-size integer; -2 for the reserved "unknown size", -1 when invalid. */
    private static long vint(RandomAccessFile in) throws IOException {
        int first = in.readUnsignedByte();
        int width = Integer.numberOfLeadingZeros(first) - 23;
        if (width < 1 || width > 8) return -1;
        long value = first & (0xFF >> width);
        boolean allOnes = value == (0xFF >> width);
        for (int i = 1; i < width; i++) {
            int next = in.readUnsignedByte();
            allOnes &= next == 0xFF;
            value = (value << 8) | next;
        }
        return allOnes ? -2 : value;
    }
}
