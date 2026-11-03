"""Execute production v13 DDL with real SQLite and synthetic v12 records only."""
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/waenhancer/xposed/core/db/RevokedSnapshotSchema.java'
DDL = [json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"',
    SOURCE.read_text().split('CREATE = {', 1)[1].split('};', 1)[0])]

STORE_SOURCE = (ROOT / 'app/src/main/java/com/waenhancer/xposed/core/db/DelMessageStore.java').read_text()
LEGACY_METHOD = STORE_SOURCE.split('public synchronized long getLegacyRevokedTimestamp', 1)[1]
LEGACY_QUERY = re.search(r'rawQuery\((.*?)new String\[\]', LEGACY_METHOD, re.S).group(1)
LEGACY_QUERY = ''.join(json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"', LEGACY_QUERY))


class AntiRevokeMigrationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = str(Path(self.directory.name) / 'delmessages.db')
        self.db = sqlite3.connect(self.path)
        self.addCleanup(self.db.close)
        self.db.executescript('''
            PRAGMA user_version=12;
            CREATE TABLE delmessages(_id INTEGER PRIMARY KEY, jid TEXT, msgid TEXT,
                timestamp INTEGER DEFAULT 0, UNIQUE(jid,msgid));
            CREATE TABLE deleted_for_me(_id INTEGER PRIMARY KEY, text_content TEXT);
            CREATE TABLE deleted_media(_id INTEGER PRIMARY KEY, message_id INTEGER, storage_id TEXT);
            CREATE TABLE deleted_media_refs(media_id INTEGER, message_id INTEGER,
                PRIMARY KEY(media_id,message_id));
            INSERT INTO deleted_for_me VALUES(1,'synthetic recovered text');
            INSERT INTO deleted_media VALUES(1,1,'synthetic storage');
            INSERT INTO deleted_media_refs VALUES(1,1);
        ''')
        self.db.executemany('INSERT INTO delmessages VALUES(?,?,?,?)',
            [(i, f'chat-{i % 3}', f'key-{i}', 1000+i) for i in range(1, 155)])
        self.db.commit()
        self.before = {t: self.db.execute(f'SELECT * FROM {t}').fetchall() for t in
            ['delmessages', 'deleted_for_me', 'deleted_media', 'deleted_media_refs']}

    def migrate(self, fail=False):
        with self.db:
            self.db.execute('BEGIN')
            for sql in DDL:
                self.db.execute(sql)
            if fail:
                self.db.execute('INSERT INTO missing_table VALUES(1)')
            self.db.execute('PRAGMA user_version=13')

    def test_v12_rows_references_and_integrity_survive(self):
        self.migrate()
        for table, rows in self.before.items():
            self.assertEqual(rows, self.db.execute(f'SELECT * FROM {table}').fetchall())
        self.assertEqual(('ok',), self.db.execute('PRAGMA integrity_check').fetchone())
        self.assertEqual((13,), self.db.execute('PRAGMA user_version').fetchone())

    def test_old_markers_read_through_production_legacy_query_after_reopen(self):
        self.migrate()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.addCleanup(self.db.close)
        for row in (1, 42, 154):
            key = f'key-{row}'
            self.assertEqual((1000+row,), self.db.execute(LEGACY_QUERY, (key, key)).fetchone())
        for table, rows in self.before.items():
            self.assertEqual(rows, self.db.execute(f'SELECT * FROM {table}').fetchall())
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM revoked_snapshots').fetchone()[0])

    def test_legacy_zero_date_is_an_existing_event_not_a_missing_row(self):
        self.db.execute("UPDATE delmessages SET timestamp=0 WHERE msgid='key-1'")
        self.db.commit()
        original = self.db.execute("SELECT * FROM delmessages WHERE msgid='key-1'").fetchone()
        self.migrate()
        self.assertEqual((0,), self.db.execute(LEGACY_QUERY, ('key-1','key-1')).fetchone())
        self.assertIsNone(self.db.execute(LEGACY_QUERY, ('missing','missing')).fetchone())
        self.assertEqual(original, self.db.execute("SELECT * FROM delmessages WHERE msgid='key-1'").fetchone())

    def test_failure_rolls_back_schema_and_version(self):
        with self.assertRaises(sqlite3.OperationalError):
            self.migrate(fail=True)
        self.assertEqual([], self.db.execute("SELECT name FROM sqlite_master WHERE name='revoked_snapshots'").fetchall())
        self.assertEqual((12,), self.db.execute('PRAGMA user_version').fetchone())
        self.assertEqual(154, self.db.execute('SELECT COUNT(*) FROM delmessages').fetchone()[0])

    def test_account_package_and_chat_identity_are_separate(self):
        self.migrate()
        for scope, chat in [('wa/default', 1), ('wa/account2', 1), ('w4b/default', 1), ('wa/default', 2)]:
            self.db.execute('INSERT INTO revoked_snapshots VALUES(?,?,?,?,?,?)',
                            (scope, 'same-key', chat, 42, 1000, b'encrypted-fixture'))
        self.assertEqual(4, self.db.execute('SELECT COUNT(*) FROM revoked_snapshots').fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO revoked_snapshots VALUES(?,?,?,?,?,?)',
                ('wa/default', 'same-key', 1, 42, 2000, b'new'))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO revoked_snapshots VALUES(?,?,?,?,?,?)',
                (None, 'key', 1, 42, 1000, b'new'))

    def test_commit_survives_immediate_process_death(self):
        self.migrate()
        subprocess.run([sys.executable, '-c', '''
import os, sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
connection.execute('PRAGMA synchronous=FULL')
with connection:
    connection.execute('INSERT INTO revoked_snapshots VALUES(?,?,?,?,?,?)',
        ('wa/default','survives',1,42,1234,b'encrypted-fixture'))
    connection.execute('INSERT INTO revoked_snapshot_aliases VALUES(?,?,?,?,?)',
        ('wa/default',42,'survives',1,1234))
os._exit(0)
''', self.path], check=True)
        self.assertEqual((1234, b'encrypted-fixture'), self.db.execute(
            "SELECT revoked_at,payload FROM revoked_snapshots WHERE scope=? AND row_id=? AND key_id=?",
            ('wa/default', 42, 'survives')).fetchone())

    def test_alias_rebind_retains_original_payload_and_both_rows(self):
        self.migrate()
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('INSERT INTO revoked_snapshots VALUES(?,?,?,?,?,?)',
            ('wa/default','stable-key',1,42,1234,b'original-encrypted-fixture'))
        for row in (42, 43):
            self.db.execute('INSERT INTO revoked_snapshot_aliases VALUES(?,?,?,?,?)',
                ('wa/default',row,'stable-key',1,1234+row))
        for row in (42, 43):
            self.assertEqual((1234,b'original-encrypted-fixture'), self.db.execute(
                'SELECT s.revoked_at,s.payload FROM revoked_snapshot_aliases a JOIN revoked_snapshots s '
                'ON s.scope=a.scope AND s.key_id=a.key_id AND s.chat_row_id=a.chat_row_id '
                'WHERE a.scope=? AND a.row_id=? AND a.key_id=?', ('wa/default',row,'stable-key')).fetchone())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO revoked_snapshot_aliases VALUES(?,?,?,?,?)',
                ('unknown-scope',99,'stable-key',1,1234))

    def test_row_and_key_queries_use_indexes(self):
        self.migrate()
        for query, args in [
            ('SELECT revoked_at FROM revoked_snapshots WHERE scope=? AND row_id=? AND key_id=?', ('s',1,'k')),
            ('SELECT timestamp FROM delmessages WHERE msgid=?', ('k',)),
            ('SELECT 1 FROM revoked_snapshots WHERE key_id=?', ('k',))]:
            plan = str(self.db.execute('EXPLAIN QUERY PLAN '+query, args).fetchall())
            self.assertIn('INDEX', plan)
            self.assertNotIn('SCAN ', plan)


if __name__ == '__main__':
    unittest.main()
