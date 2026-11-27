package com.waenhancer.library.core;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/** Real files, real SQLite, controllable clock and free-space probe. */
final class LibraryFixture implements AutoCloseable {
    static final String WA = "com.whatsapp";
    static final String W4B = "com.whatsapp.w4b";
    static final String ME = "5511999990000@s.whatsapp.net";

    final File root;
    final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    final AtomicLong usableBytes = new AtomicLong(Long.MAX_VALUE / 4);
    private final List<JdbcSql> connections = new ArrayList<>();

    LibraryFixture() throws IOException {
        root = Files.createTempDirectory("media-library-test").toFile();
    }

    File libraryDir() {
        return new File(root, "media_library");
    }

    File databaseFile() {
        return new File(root, "media_library.db");
    }

    /** Opens a new library instance (a new connection), as a restarted process would. */
    MediaLibrary open() throws IOException {
        JdbcSql sql = new JdbcSql(databaseFile());
        connections.add(sql);
        return open(sql);
    }

    MediaLibrary open(Sql sql) throws IOException {
        BlobStore blobs = new BlobStore(libraryDir(), dir -> usableBytes.get(), 64L * 1024L * 1024L);
        return new MediaLibrary(sql, blobs, new LibraryLock(libraryDir()), clock::get);
    }

    JdbcSql connection() {
        JdbcSql sql = new JdbcSql(databaseFile());
        connections.add(sql);
        return sql;
    }

    static ImportRequest.Builder request(String chat, String key) {
        return ImportRequest.builder()
                .packageName(WA)
                .ownJid(ME)
                .chatJid(chat)
                .messageKey(key)
                .fromMe(false)
                .userInitiated(true)
                .messageTimestamp(1_700_000_000L);
    }

    static MediaLibrary.StreamSource source(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    /** A stream source that fails the test if the library reads it. */
    static MediaLibrary.StreamSource unreadable(AtomicInteger opened) {
        return () -> {
            opened.incrementAndGet();
            throw new IOException("stream must not be opened");
        };
    }

    /** A minimal valid baseline JPEG (1x1); the seed goes into a COM segment so files differ. */
    private static final byte[] JPEG_1X1 = { -1,-40,-1,-32,0,16,74,70,73,70,0,1,1,1,0,72,0,72,0,0,-1,-37,0,67,0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-62,0,11,8,0,1,0,1,1,1,17,0,-1,-60,0,20,16,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1,-38,0,8,1,1,0,1,63,16 };

    static byte[] jpeg(int seed) {
        byte[] comment = ("waex-test-" + seed + "-" + "x".repeat(seed % 97)).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(JPEG_1X1, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xFE); // COM
        int length = comment.length + 2;
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(comment, 0, comment.length);
        out.write(JPEG_1X1, 2, JPEG_1X1.length - 2);
        out.write(0xFF);
        out.write(0xD9); // EOI: a complete JPEG
        return out.toByteArray();
    }

    /** A real PNG (IHDR, zlib IDAT, IEND with CRCs), 16x16 RGBA, one pixel set by the seed. */
    static byte[] png(int seed) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < 16; y++) {
            raw.write(0);
            for (int x = 0; x < 16; x++) {
                boolean on = y == 0 && x == seed % 16;
                raw.write(0); raw.write(on ? 255 : 0); raw.write(0); raw.write(on ? 255 : 0);
            }
        }
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x89); out.write('P'); out.write('N'); out.write('G');
        out.write(0x0D); out.write(0x0A); out.write(0x1A); out.write(0x0A);
        chunk(out, "IHDR", new byte[] { 0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0 });
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** A minimal movie box (moov > mvhd) so the container is structurally complete. */
    static final byte[] MOOV = moov();

    private static byte[] moov() {
        byte[] box = new byte[8 + 8 + 100];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(box);
        b.putInt(box.length).put("moov".getBytes(StandardCharsets.US_ASCII));
        b.putInt(108).put("mvhd".getBytes(StandardCharsets.US_ASCII));
        return box;
    }

    /** An ISO base-media (MP4) container: ftyp box + mdat box of {@code payload} bytes. */
    static byte[] mp4(int seed, int payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = mp4Header(payload);
        out.write(head, 0, head.length);
        byte[] data = new byte[payload];
        new Random(seed).nextBytes(data);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /** ftyp + moov + an mdat header whose size covers {@code payload} following bytes. */
    static byte[] mp4Header(long payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = { 0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '1' };
        out.write(ftyp, 0, ftyp.length);
        out.write(MOOV, 0, MOOV.length);
        long size = payload + 8;
        out.write((int) (size >>> 24) & 0xFF);
        out.write((int) (size >>> 16) & 0xFF);
        out.write((int) (size >>> 8) & 0xFF);
        out.write((int) size & 0xFF);
        out.write("mdat".getBytes(StandardCharsets.US_ASCII), 0, 4);
        return out.toByteArray();
    }

    /** A large MP4-shaped stream generated on the fly (never held in memory). */
    static MediaLibrary.StreamSource largeMp4(long seed, long payload) {
        return () -> new java.io.InputStream() {
            private final byte[] head = mp4Header(payload);
            private final Random random = new Random(seed);
            private long position;
            private final long total = head.length + payload;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (position >= total) return -1;
                int count = (int) Math.min(length, total - position);
                for (int i = 0; i < count; i++) {
                    long at = position + i;
                    buffer[offset + i] = at < head.length ? head[(int) at] : (byte) random.nextInt();
                }
                position += count;
                return count;
            }
        };
    }

    static int count(Sql sql, String table) {
        return ((Number) sql.query("SELECT COUNT(*) AS n FROM " + table).get(0).get("n")).intValue();
    }

    @Override
    public void close() throws Exception {
        for (JdbcSql sql : connections) sql.close();
        deleteRecursively(root);
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
