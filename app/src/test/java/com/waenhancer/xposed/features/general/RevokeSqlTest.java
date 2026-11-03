package com.waenhancer.xposed.features.general;

import org.junit.Test;
import static org.junit.Assert.*;

public class RevokeSqlTest {
    @Test public void deleteAcceptsWhitespaceQuotedIdAndLiveBindings() {
        for (String id : new String[]{"_id", "\"_id\"", "`_id`", "[_id]"}) {
            RevokeSql parsed = RevokeSql.parse("DELETE FROM message WHERE " + id + " = ?", new Object[]{21L});
            assertNotNull(parsed);
            assertEquals(21, parsed.row);
            assertTrue(parsed.delete);
        }
    }
    @Test public void updateSkipsSetBindingsBeforeReadingRowId() {
        RevokeSql parsed = RevokeSql.parse("UPDATE message SET text_data=?, message_type=? WHERE _id = ?",
                new Object[]{null, 15, 42});
        assertEquals(42, parsed.row);
        assertTrue(parsed.placeholder);
        assertFalse(parsed.delete);
    }
    @Test public void updateWithConflictAlgorithmAndConstantType() {
        RevokeSql parsed = RevokeSql.parse("UPDATE OR REPLACE message SET message_type=15 WHERE (_id=?)", new Object[]{44});
        assertEquals(44, parsed.row);
        assertTrue(parsed.placeholder);
    }
    @Test public void insertAndReplaceUseColumnsNotBindingOrder() {
        for (String verb : new String[]{"INSERT", "INSERT OR REPLACE", "REPLACE"}) {
            RevokeSql parsed = RevokeSql.parse(verb + " INTO message(key_id,from_me,_id,message_type) VALUES(?,?,?,?)",
                    new Object[]{"key", 0, 71, 15});
            assertEquals(71, parsed.row);
            assertTrue(parsed.placeholder);
            assertEquals("key", parsed.key);
        }
    }
    @Test public void replayWithDifferentRowCanResolveCommittedIdentity() {
        RevokeSql parsed = RevokeSql.parse("INSERT INTO message(key_id,chat_row_id,message_type,from_me) VALUES(?,?,15,0)",
                new Object[]{"key", 3});
        assertEquals(-1, parsed.row);
        assertEquals(3, parsed.chat);
        assertEquals("key", parsed.key);
    }
    @Test public void mediaDeletesResolveParentMessage() {
        RevokeSql parsed = RevokeSql.parse("DELETE FROM message_media WHERE message_row_id=?", new Object[]{91});
        assertEquals("message_media", parsed.table);
        assertEquals(91, parsed.row);
    }
    @Test public void normalUpdatesAreNotPlaceholders() {
        RevokeSql parsed = RevokeSql.parse("UPDATE message SET text_data=? WHERE _id=?", new Object[]{"edited", 1});
        assertNotNull(parsed);
        assertFalse(parsed.placeholder);
    }
    @Test public void unknownAndBulkPredicatesDoNotGuessARow() {
        for (String sql : new String[]{"DELETE FROM message", "DELETE FROM message WHERE _id IN (?,?)",
                "DELETE FROM message WHERE _id=? OR _id=?", "DELETE FROM message WHERE (_id=? OR _id=?)",
                "DELETE FROM message WHERE _id=? AND _id=?", "DELETE FROM other WHERE _id=?",
                "UPDATE message SET message_type=15 WHERE key_id=?", "DELETE FROM message WHERE _id=?; DELETE FROM message"})
            assertNull(sql, RevokeSql.parse(sql, new Object[]{1, 2}));
    }
    @Test public void additionalPredicatesAreRetainedForTheActualRowQuery() {
        RevokeSql parsed = RevokeSql.parse("DELETE FROM message WHERE _id=? AND chat_row_id=? AND from_me=?",
                new Object[]{42, 3, 0});
        assertEquals(3, parsed.predicates.size());
        assertEquals(3, parsed.predicates.get("chat_row_id"));
        assertEquals(0, parsed.predicates.get("from_me"));
    }

    @Test public void callbackScopeCoversUnknownPredicatesWithoutAffectingOtherTables() {
        assertNotNull(RevokeSql.scopedMutation("DELETE FROM message WHERE key_id=?", 42));
        assertNotNull(RevokeSql.scopedMutation("UPDATE message SET message_type=? WHERE key_id=?", 42));
        assertNotNull(RevokeSql.scopedMutation("INSERT OR REPLACE INTO message(key_id,message_type) VALUES(?,?)", 42));
        assertNull(RevokeSql.scopedMutation("UPDATE message SET text_data=? WHERE key_id=?", 42));
        assertNull(RevokeSql.scopedMutation("DELETE FROM other WHERE _id=?", 42));
        assertNull(RevokeSql.scopedMutation("DELETE FROM message WHERE _id=?", 0));
    }

    @Test public void missingBindingsAndInvalidIdsDoNotCrash() {
        assertNull(RevokeSql.parse("DELETE FROM message WHERE _id=?", null));
        assertNull(RevokeSql.parse("DELETE FROM message WHERE _id=?", new Object[]{"not-a-row"}));
        assertNull(RevokeSql.parse("DELETE FROM message WHERE _id=?", new Object[]{-1}));
    }
}
