package com.waenhancer.xposed.core.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic isolated databases only; needs Android SQLite and AndroidKeyStore, not WhatsApp. */
public class RevocationArchiveDeviceTest {
    private Context context;
    private DelMessageStore store;
    private SQLiteDatabase source;
    private File sourceFile;

    @Before public void setup() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File isolated = new File(target.getCacheDir(), "antirevoke-synthetic-" + System.nanoTime());
        assertTrue(isolated.mkdirs());
        context = new ContextWrapper(target) {
            @Override public File getDatabasePath(String name) { return new File(isolated, name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
            }
            @Override public boolean deleteDatabase(String name) { return SQLiteDatabase.deleteDatabase(getDatabasePath(name)); }
        };
        context.deleteDatabase("delmessages.db");
        store = new DelMessageStore(context);
        sourceFile = File.createTempFile("antirevoke-source", ".db", target.getCacheDir());
        source = SQLiteDatabase.openOrCreateDatabase(sourceFile, null);
        source.execSQL("CREATE TABLE message(_id INTEGER PRIMARY KEY,key_id TEXT,chat_row_id INTEGER,message_type INTEGER,from_me INTEGER,timestamp INTEGER,text_data TEXT)");
        source.execSQL("CREATE TABLE message_media(message_row_id INTEGER,file_path TEXT)");
        source.execSQL("INSERT INTO message VALUES(42,'synthetic-key',3,0,0,1234,'synthetic original')");
    }
    @After public void cleanup() {
        if (source != null) source.close();
        if (store != null) store.close();
        context.deleteDatabase("delmessages.db");
        context.getDatabasePath("delmessages.db").getParentFile().delete();
        SQLiteDatabase.deleteDatabase(sourceFile);
    }
    @Test public void missThenCaptureThenReopenRetainsTextAndTimestampWithoutJid() throws Exception {
        assertEquals(0, store.getTimestampByMessageId("synthetic-key"));
        RevocationArchive archive = new RevocationArchive(store);
        assertEquals(0, archive.timestamp(RevocationArchive.scope(source), 42, "synthetic-key"));
        long timestamp = archive.capture(source, 42, "synthetic-key");
        assertTrue(timestamp > 0);
        assertEquals(timestamp, store.getTimestampByMessageId("synthetic-key"));
        store.close();
        store = new DelMessageStore(context);
        archive = new RevocationArchive(store);
        assertEquals(timestamp, archive.timestamp(RevocationArchive.scope(source), 42, "synthetic-key"));
        assertEquals("synthetic original", archive.archivedText(RevocationArchive.scope(source), 42, "synthetic-key"));
    }
    @Test public void snapshotIsEncryptedAndCannotBeReadAsOtherAccount() throws Exception {
        RevocationArchive archive = new RevocationArchive(store);
        archive.capture(source, 42);
        String scope = RevocationArchive.scope(source);
        byte[] encrypted = store.getRevokedPayload(scope, 42, "synthetic-key");
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("synthetic original"));
        assertEquals(0, archive.timestamp(scope + "-other-account", 42, "synthetic-key"));
        assertNull(archive.archivedText(scope + "-other-account", 42, "synthetic-key"));
        assertEquals(0, store.getLegacyRevokedTimestamp("synthetic-key"));
    }
    @Test public void replayNeverOverwritesOriginalSnapshot() throws Exception {
        RevocationArchive archive = new RevocationArchive(store);
        long first = archive.capture(source, 42);
        source.execSQL("UPDATE message SET text_data='later value' WHERE _id=42");
        assertEquals(first, new RevocationArchive(store).capture(source, 42));
        assertEquals("synthetic original", archive.archivedText(RevocationArchive.scope(source), 42, "synthetic-key"));
    }
    @Test public void changedRowIdKeepsStableSnapshotAndDurableAlias() throws Exception {
        RevocationArchive archive = new RevocationArchive(store);
        long timestamp = archive.capture(source, 42);
        source.execSQL("UPDATE message SET _id=43,text_data='later version' WHERE _id=42");
        assertEquals(timestamp, archive.capture(source, 43));
        store.close();
        store = new DelMessageStore(context);
        archive = new RevocationArchive(store);
        assertEquals(timestamp, archive.timestamp(RevocationArchive.scope(source), 43, "synthetic-key"));
        assertEquals("synthetic original", archive.archivedText(RevocationArchive.scope(source), 43, "synthetic-key"));
    }

    @Test public void missingOrWrongIdentityAndPlaceholderNeverCreateFalseSuccess() throws Exception {
        RevocationArchive archive = new RevocationArchive(store);
        try { archive.capture(source, 42, "other-account-key"); fail(); }
        catch (IllegalStateException expected) { }
        source.execSQL("UPDATE message SET message_type=15 WHERE _id=42");
        try { archive.capture(source, 42); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(0, archive.timestamp(RevocationArchive.scope(source), 42, "synthetic-key"));
    }
    @Test public void oversizedContentIsNotSilentlyTruncated() throws Exception {
        source.execSQL("UPDATE message SET text_data=? WHERE _id=42", new Object[]{"x".repeat(65537)});
        try { new RevocationArchive(store).capture(source, 42); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(0, store.getTimestampByMessageId("synthetic-key"));
    }
    @Test public void mediaSnapshotKeepsReferenceWithoutCopyingFile() throws Exception {
        source.execSQL("UPDATE message SET message_type=1,text_data='synthetic caption' WHERE _id=42");
        source.execSQL("INSERT INTO message_media VALUES(42,'/nonexistent/synthetic-media.jpg')");
        RevocationArchive archive = new RevocationArchive(store);
        assertTrue(archive.capture(source, 42) > 0);
        assertEquals("synthetic caption", archive.archivedText(RevocationArchive.scope(source), 42, "synthetic-key"));
    }
    @Test public void committedTextSurvivesNativePlaceholderOrDeletion() throws Exception {
        RevocationArchive archive = new RevocationArchive(store);
        long timestamp = archive.capture(source, 42);
        source.execSQL("UPDATE message SET message_type=15,text_data=NULL WHERE _id=42");
        archive = new RevocationArchive(store);
        assertEquals("synthetic original", archive.archivedText(RevocationArchive.scope(source), 42, "synthetic-key"));
        source.execSQL("DELETE FROM message WHERE _id=42");
        assertEquals(timestamp, archive.timestamp(RevocationArchive.scope(source), 42, "synthetic-key"));
        assertEquals("synthetic original", archive.archivedText(RevocationArchive.scope(source), 42, "synthetic-key"));
    }
    @Test public void groupAndLidIdentityDoNotNeedAPhoneConversion() throws Exception {
        source.execSQL("CREATE TABLE jid(_id INTEGER PRIMARY KEY,raw_string TEXT)");
        source.execSQL("CREATE TABLE chat(_id INTEGER PRIMARY KEY,jid_row_id INTEGER)");
        source.execSQL("INSERT INTO jid VALUES(1,'synthetic-group@g.us')");
        source.execSQL("INSERT INTO chat VALUES(3,1)");
        RevocationArchive archive = new RevocationArchive(store);
        assertTrue(archive.capture(source, 42) > 0);
        source.execSQL("INSERT INTO jid VALUES(2,'synthetic-author@lid')");
        source.execSQL("INSERT INTO chat VALUES(4,2)");
        source.execSQL("INSERT INTO message VALUES(43,'lid-key',4,0,0,1234,'synthetic lid text')");
        assertTrue(archive.capture(source, 43) > 0);
        assertEquals("synthetic lid text", archive.archivedText(RevocationArchive.scope(source), 43, "lid-key"));
    }

    @Test public void tamperedCiphertextFailsAuthentication() throws Exception {
        RevokedSnapshotCrypto crypto = new RevokedSnapshotCrypto();
        byte[] encrypted = crypto.encrypt("synthetic content".getBytes(StandardCharsets.UTF_8), "account+message");
        encrypted[encrypted.length - 1] ^= 1;
        try { crypto.decrypt(encrypted, "account+message"); fail(); }
        catch (java.security.GeneralSecurityException expected) { }
    }
    @Test public void version12MigrationPreservesAllFourTables() {
        store.close();
        context.deleteDatabase("delmessages.db");
        try (SQLiteDatabase v12 = context.openOrCreateDatabase("delmessages.db", 0, null)) {
            v12.execSQL("CREATE TABLE delmessages(_id INTEGER PRIMARY KEY,jid TEXT,msgid TEXT,timestamp INTEGER,UNIQUE(jid,msgid))");
            v12.execSQL("CREATE TABLE deleted_for_me(_id INTEGER PRIMARY KEY,text_content TEXT)");
            v12.execSQL("CREATE TABLE deleted_media(_id INTEGER PRIMARY KEY,message_id INTEGER)");
            v12.execSQL("CREATE TABLE deleted_media_refs(media_id INTEGER,message_id INTEGER,PRIMARY KEY(media_id,message_id))");
            for (int i = 1; i <= 154; i++) v12.execSQL("INSERT INTO delmessages VALUES(?,?,?,?)",
                    new Object[]{i,"synthetic-chat","synthetic-"+i,1000+i});
            v12.execSQL("INSERT INTO deleted_for_me VALUES(1,'synthetic recovered')");
            v12.execSQL("INSERT INTO deleted_media VALUES(1,1)");
            v12.execSQL("INSERT INTO deleted_media_refs VALUES(1,1)");
            v12.setVersion(12);
        }
        store = new DelMessageStore(context);
        SQLiteDatabase v13 = store.getWritableDatabase();
        assertEquals(13, v13.getVersion());
        for (String table : new String[]{"delmessages","deleted_for_me","deleted_media","deleted_media_refs"}) {
            try (Cursor count = v13.rawQuery("SELECT COUNT(*) FROM "+table, null)) {
                assertTrue(count.moveToFirst());
                assertEquals(table.equals("delmessages") ? 154 : 1, count.getInt(0));
            }
        }
        try (Cursor integrity = v13.rawQuery("PRAGMA integrity_check", null)) {
            assertTrue(integrity.moveToFirst());
            assertEquals("ok", integrity.getString(0));
        }
    }
}
