package com.waenhancer.library.core;

import static com.waenhancer.library.core.LibraryFixture.jpeg;
import static com.waenhancer.library.core.LibraryFixture.request;
import static com.waenhancer.library.core.LibraryFixture.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LibrarySchemaTest {
    private LibraryFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new LibraryFixture();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    public void freshDatabaseIsCreatedAtTheCurrentVersionWithConstraints() throws Exception {
        JdbcSql sql = fixture.connection();
        fixture.open(sql);
        assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
        for (String table : LibrarySchema.TABLES) {
            assertFalse(table, sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", table).isEmpty());
        }
        try {
            sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at) VALUES ('short', 1, 'x', 0, 0)");
            fail("CHECK(length(sha256)=64) must hold");
        } catch (IllegalStateException expected) {
            // constraint enforced by SQLite itself
        }
        try {
            sql.insert("INSERT INTO items (uuid, account_id, chat_jid, message_key, from_me, media_kind, mime_type, sha256, "
                    + "imported_at, basis) VALUES ('u', 999, 'c', 'k', 0, 'image', 'image/jpeg', '" + "a".repeat(64) + "', 0, 'x')");
            fail("foreign keys must be enforced");
        } catch (IllegalStateException expected) {
            // accounts/blobs foreign keys
        }
    }

    @Test
    public void reopeningIsANoOpAndPreservesData() throws Exception {
        MediaLibrary library = fixture.open();
        library.importMedia(request("c@s.whatsapp.net", "K1").build(), source(jpeg(1)));
        JdbcSql second = fixture.connection();
        new LibrarySchema().migrate(second);
        fixture.open(second);
        assertEquals(1, LibraryFixture.count(second, "items"));
    }

    @Test
    public void databaseFromANewerModuleIsRefusedAndLeftUntouched() throws Exception {
        JdbcSql sql = fixture.connection();
        MediaLibrary library = fixture.open(sql);
        library.importMedia(request("c@s.whatsapp.net", "K1").build(), source(jpeg(1)));
        sql.inTransaction(() -> {
            sql.setUserVersion(LibrarySchema.CURRENT_VERSION + 5);
            return null;
        });
        try {
            fixture.open(fixture.connection());
            fail("a downgrade must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.SCHEMA_TOO_NEW, expected.code);
        }
        assertEquals(LibrarySchema.CURRENT_VERSION + 5, sql.userVersion());
        assertEquals(1, LibraryFixture.count(sql, "items"));
    }

    @Test
    public void failingFutureStepRollsBackEverythingAndKeepsTheOldVersion() throws Exception {
        JdbcSql sql = fixture.connection();
        MediaLibrary library = fixture.open(sql);
        library.importMedia(request("c@s.whatsapp.net", "K1").build(), source(jpeg(1)));

        List<LibrarySchema.Step> steps = new ArrayList<>(LibrarySchema.defaultSteps());
        steps.add(step(LibrarySchema.CURRENT_VERSION + 1, s -> {
            s.exec("ALTER TABLE items ADD COLUMN rating INTEGER");
            s.exec("CREATE TABLE half_done (x INTEGER)");
            throw new IllegalStateException("power cut in the middle of the step");
        }));
        try {
            new LibrarySchema(steps, LibrarySchema.CURRENT_VERSION + 1).migrate(sql);
            fail("the failing step must surface");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.IO, expected.code);
        }
        assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
        assertTrue(sql.query("SELECT 1 FROM sqlite_master WHERE name='half_done'").isEmpty());
        assertTrue(sql.query("SELECT 1 FROM pragma_table_info('items') WHERE name='rating'").isEmpty());
        assertEquals(1, LibraryFixture.count(sql, "items"));
    }

    @Test
    public void stepThatWouldDropRowsIsRefused() throws Exception {
        JdbcSql sql = fixture.connection();
        MediaLibrary library = fixture.open(sql);
        library.importMedia(request("c@s.whatsapp.net", "K1").build(), source(jpeg(1)));
        List<LibrarySchema.Step> steps = new ArrayList<>(LibrarySchema.defaultSteps());
        steps.add(step(LibrarySchema.CURRENT_VERSION + 1, s -> s.exec("DELETE FROM items")));
        try {
            new LibrarySchema(steps, LibrarySchema.CURRENT_VERSION + 1).migrate(sql);
            fail("a destructive step must be refused");
        } catch (LibraryException expected) {
            assertEquals(LibraryException.Code.IO, expected.code);
        }
        assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
        assertEquals(1, LibraryFixture.count(sql, "items"));
    }

    @Test
    public void additiveFutureStepMigratesAndKeepsRows() throws Exception {
        JdbcSql sql = fixture.connection();
        MediaLibrary library = fixture.open(sql);
        library.importMedia(request("c@s.whatsapp.net", "K1").build(), source(jpeg(1)));
        List<LibrarySchema.Step> steps = new ArrayList<>(LibrarySchema.defaultSteps());
        steps.add(step(LibrarySchema.CURRENT_VERSION + 1, s -> s.exec("ALTER TABLE items ADD COLUMN favourite INTEGER NOT NULL DEFAULT 0")));
        new LibrarySchema(steps, LibrarySchema.CURRENT_VERSION + 1).migrate(sql);
        assertEquals(LibrarySchema.CURRENT_VERSION + 1, sql.userVersion());
        assertEquals(1, LibraryFixture.count(sql, "items"));
        assertFalse(sql.query("SELECT 1 FROM pragma_table_info('items') WHERE name='favourite'").isEmpty());
    }

    private static LibrarySchema.Step step(int version, java.util.function.Consumer<Sql> body) {
        return new LibrarySchema.Step() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public void apply(Sql sql) {
                body.accept(sql);
            }
        };
    }

    @Test
    public void version1CatalogMigratesToVersion2KeepingEveryRowAndMarkingViewOnce() throws Exception {
        JdbcSql sql = fixture.connection();
        new LibrarySchema(LibrarySchema.defaultSteps().subList(0, 1), 1).migrate(sql);
        assertEquals(1, sql.userVersion());
        String sha = "a".repeat(64);
        sql.insert("INSERT INTO accounts (package_name, own_jid, created_at) VALUES ('com.whatsapp', 'me', 0)");
        sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at) VALUES (?, 10, 'image/jpeg', 0, 0)", sha);
        sql.insert("INSERT INTO items (uuid, account_id, chat_jid, message_key, from_me, media_kind, mime_type, sha256, "
                + "imported_at, basis) VALUES ('u1', 1, 'c', 'K1', 0, 'image', 'image/jpeg', ?, 0, 'auto_view_once')", sha);
        sql.insert("INSERT INTO items (uuid, account_id, chat_jid, message_key, from_me, media_kind, mime_type, sha256, "
                + "imported_at, basis) VALUES ('u2', 1, 'c', 'K2', 0, 'image', 'image/jpeg', ?, 0, 'user_explicit')", sha);

        MediaLibrary library = fixture.open(sql); // migrates 1 -> 2
        assertEquals(2, sql.userVersion());
        assertEquals(2, LibraryFixture.count(sql, "items"));
        assertTrue(library.get("u1").viewOnce);
        assertFalse(library.get("u2").viewOnce);
    }
}
