package com.waenhancer.xposed.core.db;

/** Versions 4–6 already use message_key. Preserve their history and original receipt rows. */
final class MessageHistoryMigration {
    private MessageHistoryMigration() { }

    static final String[] TO_V7 = {
            "ALTER TABLE hide_seen_messages RENAME TO hide_seen_messages_legacy_v7",
            "DROP INDEX IF EXISTS idx_hide_seen_unique",
            "DROP INDEX IF EXISTS idx_hide_seen_query",
            "CREATE TABLE hide_seen_messages(_id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL, message_id TEXT NOT NULL, type INT NOT NULL, viewed INT DEFAULT 0)",
            "INSERT INTO hide_seen_messages(_id,jid,message_id,type,viewed) SELECT MIN(_id),jid,message_id,type,MAX(viewed) FROM hide_seen_messages_legacy_v7 GROUP BY jid,message_id,type",
            "CREATE INDEX IF NOT EXISTS idx_message_history_key ON MessageHistory(message_key)",
            "CREATE UNIQUE INDEX idx_hide_seen_unique ON hide_seen_messages(jid,message_id,type)",
            "CREATE INDEX idx_hide_seen_query ON hide_seen_messages(jid,type,viewed)"
    };

    static final String EXPECTED_COUNT = "SELECT COUNT(*) FROM (SELECT jid,message_id,type FROM hide_seen_messages_legacy_v7 GROUP BY jid,message_id,type)";
    static final String ACTUAL_COUNT = "SELECT COUNT(*) FROM hide_seen_messages";
}
