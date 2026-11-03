package com.waenhancer.xposed.core.db;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.waenhancer.xposed.features.general.RevocationMarkerLookup;
import java.io.File;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic metadata only. No WhatsApp, media files, or encryption keys are consulted. */
public class RevocationLinkQueryDeviceTest {
    private File directory;
    private Context context;
    private SQLiteDatabase source;
    private DelMessageStore store;
    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(target.getCacheDir(), "marker-links-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        context = new ContextWrapper(target) {
            @Override public File getDatabasePath(String name) { return new File(directory, name); }
        };
        source = SQLiteDatabase.openOrCreateDatabase(new File(directory, "source.db"), null);
        source.execSQL("CREATE TABLE message(_id INTEGER PRIMARY KEY,key_id TEXT,from_me INTEGER,text_data TEXT)");
        source.execSQL("INSERT INTO message VALUES(42,'current-key',0,'retained synthetic content')");
        source.execSQL("CREATE TABLE message_add_on(parent_message_row_id INTEGER,key_id TEXT)");
        store = new DelMessageStore(context);
    }
    @After public void cleanup() {
        source.close(); store.close();
        SQLiteDatabase.deleteDatabase(new File(directory,"source.db"));
        SQLiteDatabase.deleteDatabase(new File(directory,"delmessages.db")); directory.delete();
    }
    private String scope() { return RevocationArchive.scope(source); }
    private void indexLinks() { source.execSQL("CREATE INDEX parent_links ON message_add_on(parent_message_row_id)"); }
    @Test public void changedMessageKeyRestoresOldMarkerAfterReopenWithoutChangingSourceRows() {
        indexLinks();
        source.execSQL("INSERT INTO message_add_on VALUES(42,'old-key')");
        store.insertMessage("synthetic-jid", "old-key", 1234);
        store.close(); store = new DelMessageStore(context);
        List<String> keys = new RevocationLinkQuery().read(source,scope(),42,"current-key");
        assertEquals(List.of("old-key"), keys);
        var match = RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE,keys,key -> {
            var marker = store.getLinkedRevokedMarker(scope(),key);
            return marker.known() ? new RevocationMarkerLookup.Match(key,marker.timestamp()) : RevocationMarkerLookup.Match.NONE;
        });
        assertEquals("old-key",match.key()); assertEquals(1234,match.timestamp());
        try (Cursor row=source.rawQuery("SELECT key_id,text_data FROM message WHERE _id=42",null)) {
            assertTrue(row.moveToFirst()); assertEquals("current-key",row.getString(0));
            assertEquals("retained synthetic content",row.getString(1));
        }
        assertEquals(0,store.getTimestampByMessageId("current-key"));
    }
    @Test public void staleKeyWrongAccountAndOutgoingRowCannotContributeLinks() {
        indexLinks(); source.execSQL("INSERT INTO message_add_on VALUES(42,'old-key')");
        var query = new RevocationLinkQuery();
        assertTrue(query.read(source,scope()+"-other",42,"current-key").isEmpty());
        assertTrue(query.read(source,scope(),42,"reused-key").isEmpty());
        source.execSQL("UPDATE message SET from_me=1 WHERE _id=42");
        assertTrue(query.read(source,scope(),42,"current-key").isEmpty());
    }
    @Test public void unindexedAndTruncatedRelationsDoNotScanOrGuess() {
        source.execSQL("INSERT INTO message_add_on VALUES(42,'old-key')");
        assertTrue(new RevocationLinkQuery().read(source,scope(),42,"current-key").isEmpty());
        indexLinks();
        for(int i=0;i<8;i++) source.execSQL("INSERT INTO message_add_on VALUES(42,?)",new Object[]{"candidate-"+i});
        assertTrue(new RevocationLinkQuery().read(source,scope(),42,"current-key").isEmpty());
    }
    @Test public void unrelatedAddonIsNotARevocationRecord() {
        indexLinks(); source.execSQL("INSERT INTO message_add_on VALUES(42,'unknown-key')");
        List<String> keys=new RevocationLinkQuery().read(source,scope(),42,"current-key");
        assertEquals(RevocationMarkerLookup.Match.NONE, RevocationMarkerLookup.resolve(RevocationMarkerLookup.Match.NONE,keys,key -> {
            var marker = store.getLinkedRevokedMarker(scope(),key);
            return marker.known() ? new RevocationMarkerLookup.Match(key,marker.timestamp()) : RevocationMarkerLookup.Match.NONE;
        }));
    }
    @Test public void scopedRecordsCannotLeakThroughLegacyFallbackOrChooseBetweenChats() {
        store.commitRevocation("account-a","old-key",1,42,"synthetic",1234,new byte[]{1});
        assertEquals(1234,store.getLinkedRevokedTimestamp("account-a","old-key"));
        assertEquals(0,store.getLinkedRevokedTimestamp("account-b","old-key"));
        store.commitRevocation("account-a","old-key",2,43,"synthetic",2345,new byte[]{1});
        assertEquals(0,store.getLinkedRevokedTimestamp("account-a","old-key"));
    }
    @Test public void legacyZeroDateIsDistinctFromMissingAndSurvivesReopen() {
        store.insertMessage("synthetic-jid","old-key",0);
        assertTrue(store.getLegacyRevokedMarker("old-key").known());
        assertEquals(0,store.getLegacyRevokedMarker("old-key").timestamp());
        assertFalse(store.getLegacyRevokedMarker("missing-key").known());
        store.close(); store = new DelMessageStore(context);
        assertTrue(store.getLinkedRevokedMarker(scope(),"old-key").known());
        assertEquals(0,store.getLinkedRevokedMarker(scope(),"old-key").timestamp());
    }

}
