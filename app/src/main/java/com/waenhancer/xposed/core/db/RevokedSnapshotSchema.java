package com.waenhancer.xposed.core.db;

/** Additive v13 migration. Shared verbatim with the host SQLite regression suite. */
public final class RevokedSnapshotSchema {
    private RevokedSnapshotSchema() {}
    public static final String TABLE = "revoked_snapshots";
    public static final String[] CREATE = {
        "CREATE TABLE IF NOT EXISTS revoked_snapshots (scope TEXT NOT NULL, key_id TEXT NOT NULL, chat_row_id INTEGER NOT NULL, row_id INTEGER NOT NULL, revoked_at INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(scope,key_id,chat_row_id))",
        "CREATE TABLE IF NOT EXISTS revoked_snapshot_aliases (scope TEXT NOT NULL, row_id INTEGER NOT NULL, key_id TEXT NOT NULL, chat_row_id INTEGER NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(scope,row_id,key_id), FOREIGN KEY(scope,key_id,chat_row_id) REFERENCES revoked_snapshots(scope,key_id,chat_row_id))",
        "CREATE INDEX IF NOT EXISTS idx_revoked_alias_event ON revoked_snapshot_aliases(scope,key_id,chat_row_id,observed_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_snapshot_row ON revoked_snapshots(scope,row_id,key_id)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_snapshot_key ON revoked_snapshots(key_id)",
        "CREATE INDEX IF NOT EXISTS idx_delmessages_msgid ON delmessages(msgid)"
    };
}
