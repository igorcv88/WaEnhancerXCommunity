"""Run the production migration SQL with SQLite: python3 tools/test_message_history_migration.py."""
import json
from pathlib import Path
import re
import sqlite3
import unittest


SOURCE = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer/"
          "xposed/core/db/MessageHistoryMigration.java").read_text()
SQL = [json.loads(value) for value in re.findall(
    r'"(?:[^"\\]|\\.)*"', SOURCE.split("TO_V7 = {", 1)[1].split("};", 1)[0])]


class HistoryMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE MessageHistory(_id INTEGER PRIMARY KEY, message_key TEXT NOT NULL,
                text_data TEXT NOT NULL, editTimestamp BIGINT DEFAULT 0);
            INSERT INTO MessageHistory VALUES(1,'message-key','original edit',123);
            CREATE TABLE hide_seen_messages(_id INTEGER PRIMARY KEY, jid TEXT NOT NULL,
                message_id TEXT NOT NULL, type INT NOT NULL, viewed INT DEFAULT 0);
            INSERT INTO hide_seen_messages VALUES(1,'author@lid','id',0,0);
            INSERT INTO hide_seen_messages VALUES(2,'author@lid','id',0,1);
            INSERT INTO hide_seen_messages VALUES(3,'author@lid','id',1,0);
            INSERT INTO hide_seen_messages VALUES(4,'other@lid','id',0,0);
        """)
        self.original_history = self.db.execute("SELECT * FROM MessageHistory").fetchall()
        self.original_receipts = self.db.execute("SELECT * FROM hide_seen_messages").fetchall()

    def migrate(self):
        with self.db:
            # Match SQLiteOpenHelper's explicit transaction, including all DDL.
            self.db.execute("BEGIN")
            for sql in SQL:
                self.db.execute(sql)

    def test_history_and_original_receipt_rows_survive(self):
        self.migrate()
        self.assertEqual(self.original_history,
                         self.db.execute("SELECT * FROM MessageHistory").fetchall())
        self.assertEqual(self.original_receipts,
                         self.db.execute("SELECT * FROM hide_seen_messages_legacy_v7").fetchall())

    def test_duplicates_keep_authorization_and_receipt_types_separate(self):
        self.migrate()
        self.assertEqual([('author@lid', 'id', 0, 1), ('author@lid', 'id', 1, 0),
                          ('other@lid', 'id', 0, 0)], self.db.execute(
                              "SELECT jid,message_id,type,viewed FROM hide_seen_messages "
                              "ORDER BY jid,type").fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO hide_seen_messages(jid,message_id,type) "
                            "VALUES('author@lid','id',0)")

    def test_existing_nonunique_indexes_do_not_attach_to_wrong_table(self):
        self.db.execute("CREATE INDEX idx_hide_seen_query ON hide_seen_messages(jid,type,viewed)")
        self.db.execute("CREATE INDEX idx_message_history_key ON MessageHistory(message_key)")
        self.migrate()
        self.assertEqual('hide_seen_messages', self.db.execute(
            "SELECT tbl_name FROM sqlite_master WHERE name='idx_hide_seen_query'").fetchone()[0])

    def test_late_failure_rolls_back_the_original_tables(self):
        # Simulate an unexpected legacy schema: migration fails after its copy step.
        self.db.execute("DROP TABLE MessageHistory")
        self.db.execute("CREATE TABLE MessageHistory(_id INTEGER PRIMARY KEY, row_id INT)")
        self.db.execute("INSERT INTO MessageHistory VALUES(9,1234)")
        self.db.commit()
        with self.assertRaises(sqlite3.OperationalError):
            self.migrate()
        self.assertEqual(self.original_receipts,
                         self.db.execute("SELECT * FROM hide_seen_messages").fetchall())
        self.assertEqual([(9, 1234)], self.db.execute("SELECT * FROM MessageHistory").fetchall())
        self.assertEqual([], self.db.execute(
            "SELECT name FROM sqlite_master WHERE name='hide_seen_messages_legacy_v7'").fetchall())

    def test_archive_name_collision_preserves_source(self):
        self.db.execute("CREATE TABLE hide_seen_messages_legacy_v7(marker TEXT)")
        self.db.commit()
        with self.assertRaises(sqlite3.OperationalError):
            self.migrate()
        self.assertEqual(self.original_receipts,
                         self.db.execute("SELECT * FROM hide_seen_messages").fetchall())


if __name__ == "__main__":
    unittest.main()
