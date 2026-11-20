#!/usr/bin/env python3
"""Execute the shipped migration against real SQLite, including transactional failures."""
import pathlib
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQL = (ROOT / 'app/src/main/assets/database/gea-v5.sql').read_text()
STATEMENTS = [s.strip() for s in SQL.split(';') if s.strip()]
TABLES = ['rsi_promotion_ledger', 'subjective_beliefs', 'ecological_niche_structures',
          'tsetlin_automata_blobs', 'adico_institutions', 'double_entry_ledger',
          'bkt_fsrs_mastery', 'voyager_skill_library']
ROWS = [
    (1, 8, None, '{}', 'a'*64),
    (1, 'agent', 'claim', 'source', .2, .3, .5, .5),
    (1, 'structure', 'agent', 20, '{}'),
    (1, 'agent', 1, b'\x01\x02', 'a'*64),
    (1, 'rule', 'agent', 'PERMITIDO', 'study', 'awake', 'lose-access'),
    (1, 0, 'agent', 'market', 'coins', 1),
    (1, 'agent', 'study', .8, 5., 2., 0., 3, 1),
    (1, 'agent', 'study', 3, .8, 'awake', 'STUDY'),
]


def baseline(path=':memory:'):
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('CREATE TABLE events(id INTEGER PRIMARY KEY,tick INTEGER NOT NULL UNIQUE,payload TEXT NOT NULL)')
    db.execute("INSERT INTO events VALUES(1,1,'historical-bytes')")
    db.execute('PRAGMA user_version=4')
    db.commit()
    return db


def migrate(db):
    db.execute("BEGIN")
    with db:
        for statement in STATEMENTS:
            db.execute(statement)
        db.execute('PRAGMA user_version=5')


def insert(db, table, row):
    db.execute(f'INSERT INTO {table} VALUES({",".join("?" for _ in row)})', row)


class MigrationTest(unittest.TestCase):
    def test_migration_preserves_history_and_all_tables_reject_orphans(self):
        with baseline() as db:
            migrate(db)
            self.assertEqual(db.execute('SELECT payload FROM events').fetchone()[0], 'historical-bytes')
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 5)
            for table, row in zip(TABLES, ROWS):
                insert(db, table, row)
                with self.assertRaises(sqlite3.IntegrityError):
                    insert(db, table, (99, *row[1:]))
                self.assertTrue(db.execute(f'PRAGMA index_list({table})').fetchall())
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute('DELETE FROM events WHERE id=1')
            self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_interrupted_migration_rolls_back_schema_and_version(self):
        with baseline() as db:
            def deny(action, name, *_):
                return sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_CREATE_TABLE and name == TABLES[4] else sqlite3.SQLITE_OK
            db.set_authorizer(deny)
            with self.assertRaises(sqlite3.DatabaseError):
                migrate(db)
            db.set_authorizer(None)
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 4)
            self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall(), [('events',)])
            migrate(db)

    def test_domains_and_blob_budget(self):
        with baseline() as db:
            migrate(db)
            invalid = [
                (TABLES[1], (1, 'a', 'c', 's', .7, .7, .0, .5)),
                (TABLES[2], (1, 's', 'a', 0, '{}')),
                (TABLES[3], (1, 'a', 1, bytes(1048577), 'a'*64)),
                (TABLES[4], (1, 'r', 'a', 'INVALID', 'x', 'x', 'x')),
                (TABLES[5], (1, 0, 'a', 'a', 'coins', 1)),
                (TABLES[5], (1, 0, 'a', 'b', 'coins', 1.5)),
                (TABLES[6], (1, 'a', 's', .8, 5., 0., 0., 3, 1)),
                (TABLES[7], (1, 'a', 's', 2, .8, 'awake', 'STUDY')),
            ]
            for table, row in invalid:
                with self.subTest(table=table), self.assertRaises(sqlite3.IntegrityError):
                    insert(db, table, row)

    def test_full_disk_rolls_back_event_and_blob(self):
        with tempfile.TemporaryDirectory() as directory:
            db = baseline(str(pathlib.Path(directory)/'world.db'))
            try:
                migrate(db)
                pages = db.execute('PRAGMA page_count').fetchone()[0]
                db.execute(f'PRAGMA max_page_count={pages}')
                with self.assertRaises(sqlite3.OperationalError) as failure:
                    with db:
                        db.execute("INSERT INTO events VALUES(2,2,'new')")
                        insert(db, TABLES[3], (2, 'a', 1, bytes(1048576), 'a'*64))
                self.assertEqual(failure.exception.sqlite_errorcode, sqlite3.SQLITE_FULL)
                self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 1)
                self.assertEqual(db.execute('SELECT count(*) FROM tsetlin_automata_blobs').fetchone()[0], 0)
            finally:
                db.close()

    def test_constraint_failure_rolls_back_event_and_other_writes(self):
        with baseline() as db:
            migrate(db)
            with self.assertRaises(sqlite3.IntegrityError):
                with db:
                    db.execute("INSERT INTO events VALUES(2,2,'new')")
                    insert(db, TABLES[3], (2, 'a', 1, bytes(516), 'a'*64))
                    insert(db, TABLES[5], (2, 0, 'same', 'same', 'coins', 1))
            self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 1)
            self.assertEqual(db.execute('SELECT count(*) FROM tsetlin_automata_blobs').fetchone()[0], 0)

    def test_economy_projection_double_entries_and_infrastructure_commit_atomically(self):
        with baseline() as db:
            migrate(db)
            with db:
                db.execute("INSERT INTO events VALUES(2,2,'economic-event')")
                insert(db, TABLES[5], (2, 0, 'tools', 'market', 'timber', 1))
                insert(db, TABLES[5], (2, 1, 'structures', 'market', 'timber', 4))
                insert(db, TABLES[2], (2, 'annex_research', 'agent', 2402, '{"investedTimber":4}'))
            deltas = dict(db.execute("""SELECT account, sum(delta) FROM (
              SELECT debit_account AS account, amount AS delta FROM double_entry_ledger WHERE resource='timber'
              UNION ALL SELECT credit_account, -amount FROM double_entry_ledger WHERE resource='timber'
            ) GROUP BY account"""))
            self.assertEqual(deltas, {'market': -5, 'structures': 4, 'tools': 1})
            self.assertEqual(sum(deltas.values()), 0)
            with self.assertRaises(sqlite3.IntegrityError):
                with db:
                    db.execute("INSERT INTO events VALUES(3,3,'must-roll-back')")
                    insert(db, TABLES[5], (3, 0, 'market', 'COMMUNAL_COMMONS', 'coins', 1))
                    insert(db, TABLES[2], (3, 'invalid-expiry', 'agent', 2, '{}'))
            self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT count(*) FROM double_entry_ledger WHERE event_id=3').fetchone()[0], 0)
            self.assertEqual(db.execute('SELECT count(*) FROM ecological_niche_structures WHERE event_id=3').fetchone()[0], 0)

    def test_beliefs_commit_with_event_and_invalid_projection_rolls_back(self):
        with baseline() as db:
            migrate(db)
            with db:
                db.execute("INSERT INTO events VALUES(2,2,'belief-event')")
                insert(db, TABLES[1], (2, 'agent', 'c'*64, 'd'*64, .57, 0., .43, .5))
            self.assertEqual(db.execute('SELECT belief+disbelief+uncertainty FROM subjective_beliefs').fetchone()[0], 1.)
            with self.assertRaises(sqlite3.IntegrityError), db:
                db.execute("INSERT INTO events VALUES(3,3,'rejected-belief-event')")
                insert(db, TABLES[1], (3, 'agent', 'c'*64, 'd'*64, .8, 0., .5, .5))
            self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT count(*) FROM subjective_beliefs').fetchone()[0], 1)
            with self.assertRaises(sqlite3.IntegrityError), db:
                insert(db, TABLES[1], (2, 'agent', 'c'*64, 'd'*64, .57, 0., .43, .5))
            self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_institution_and_sanction_ledger_commit_or_roll_back_together(self):
        with baseline() as db:
            migrate(db)
            with db:
                db.execute("INSERT INTO events VALUES(2,2,'institution-event')")
                insert(db, TABLES[4], (2, 'rule', 'ALL', 'PROIBIDO', 'BUILD', 'TIMBER_LT:8', 'FINE:1'))
                insert(db, TABLES[5], (2, 0, 'COMMUNAL_COMMONS', 'agent', 'coins', 1))
            with self.assertRaises(sqlite3.IntegrityError), db:
                db.execute("INSERT INTO events VALUES(3,3,'invalid-institution-event')")
                insert(db, TABLES[5], (3, 0, 'COMMUNAL_COMMONS', 'agent', 'coins', 2))
                insert(db, TABLES[4], (3, 'bad', 'ALL', 'EXECUTE', 'BUILD', 'ALWAYS', 'FINE:1'))
            self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT count(*) FROM double_entry_ledger WHERE event_id=3').fetchone()[0], 0)
            self.assertEqual(db.execute('SELECT count(*) FROM adico_institutions').fetchone()[0], 1)

    def test_corrupt_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)/'world.db'
            db = baseline(str(path))
            migrate(db)
            db.close()
            with path.open('r+b') as stream:
                stream.write(b'CORRUPTED HEADER!')
            db = sqlite3.connect(path)
            try:
                with self.assertRaises(sqlite3.DatabaseError):
                    db.execute('PRAGMA quick_check').fetchall()
            finally:
                db.close()

    def test_fork_copies_only_selected_event_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            source = str(pathlib.Path(directory)/'source.db')
            db = baseline(source)
            migrate(db)
            with db:
                db.execute("INSERT INTO events VALUES(2,2,'future')")
                for table, row in zip(TABLES, ROWS):
                    insert(db, table, row)
                    future = (2, *row[1:])
                    insert(db, table, future)
            db.close()
            target = sqlite3.connect(':memory:')
            try:
                target.execute('PRAGMA foreign_keys=ON')
                target.execute('CREATE TABLE events(id INTEGER PRIMARY KEY,tick INTEGER NOT NULL UNIQUE,payload TEXT NOT NULL)')
                migrate(target)
                target.execute('ATTACH DATABASE ? AS origin', (source,))
                with target:
                    target.execute('INSERT INTO events SELECT * FROM origin.events WHERE id<=1')
                    for table in TABLES:
                        target.execute(f'INSERT INTO {table} SELECT * FROM origin.{table} WHERE event_id<=1')
                        self.assertEqual(target.execute(f'SELECT event_id FROM {table}').fetchall(), [(1,)])
                self.assertEqual(target.execute('PRAGMA foreign_key_check').fetchall(), [])
            finally:
                target.close()


V6 = (ROOT / 'app/src/main/assets/database/gea-v6.sql').read_text().split('-- @statement')


def migrate6(db):
    db.execute('BEGIN')
    with db:
        for statement in V6:
            db.execute(statement)
        db.execute('PRAGMA user_version=6')


def trial(db, stage='PROPOSED', identity='b'*64, payload='{}'):
    db.execute('INSERT INTO rsi_policy_trials(event_id,source_event_id,trial_id,stage,rules_version,payload,digest) VALUES(?,?,?,?,?,?,?)',
               (1, 1, identity, stage, 8, payload, 'a'*64))


class RsiMigrationTest(unittest.TestCase):
    def test_schema6_preserves_all_schema5_evidence_and_immutable_trials(self):
        with baseline() as db:
            migrate(db)
            for table, row in zip(TABLES, ROWS):
                insert(db, table, row)
            db.commit()
            migrate6(db)
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 6)
            for table, row in zip(TABLES, ROWS):
                self.assertEqual(db.execute(f'SELECT * FROM {table}').fetchone(), row)
            with db:
                trial(db)
                trial(db, 'TRAINED')
                trial(db, 'REJECTED')
            for sql in ["UPDATE rsi_policy_trials SET payload='changed'", 'DELETE FROM rsi_policy_trials']:
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute(sql)
            with self.assertRaises(sqlite3.IntegrityError), db:
                trial(db, 'VERIFIED')
            with self.assertRaises(sqlite3.IntegrityError), db:
                trial(db, 'REJECTED', 'c'*64)
            self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_interrupted_schema6_rolls_back_without_losing_schema5(self):
        with baseline() as db:
            migrate(db)
            insert(db, TABLES[0], ROWS[0])
            db.commit()
            db.set_authorizer(lambda action, name, *_: sqlite3.SQLITE_DENY if action==sqlite3.SQLITE_CREATE_INDEX and name=='rsi_one_terminal' else sqlite3.SQLITE_OK)
            with self.assertRaises(sqlite3.DatabaseError):
                migrate6(db)
            db.set_authorizer(None)
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 5)
            self.assertEqual(db.execute('SELECT * FROM rsi_promotion_ledger').fetchone(), ROWS[0])
            self.assertEqual(db.execute("SELECT count(*) FROM sqlite_master WHERE name='rsi_policy_trials'").fetchone()[0], 0)
            migrate6(db)

    def test_fork_excludes_experiment_written_after_selected_tick(self):
        with tempfile.TemporaryDirectory() as directory:
            source=str(pathlib.Path(directory)/'source.db')
            db=baseline(source)
            migrate(db); migrate6(db)
            with db:
                db.execute("INSERT INTO events VALUES(2,2,'future')")
                for stage in ('PROPOSED', 'TRAINED', 'REJECTED'):
                    db.execute("INSERT INTO rsi_policy_trials(event_id,source_event_id,trial_id,stage,rules_version,payload,digest) VALUES(2,1,?,?,8,'{}',?)", ('b'*64, stage, 'a'*64))
            db.close()
            target=baseline()
            try:
                migrate(target); migrate6(target)
                target.execute('ATTACH DATABASE ? AS origin', (source,))
                with target:
                    target.execute('INSERT INTO rsi_policy_trials SELECT * FROM origin.rsi_policy_trials WHERE event_id<=1')
                self.assertEqual(target.execute('SELECT count(*) FROM rsi_policy_trials').fetchone()[0], 0)
                with target:
                    target.execute("INSERT INTO events VALUES(2,2,'future')")
                    # Deferred self-FK permits a whole prefix even with terminals first.
                    target.execute('INSERT INTO rsi_policy_trials SELECT * FROM origin.rsi_policy_trials WHERE event_id<=2 ORDER BY stage DESC')
                self.assertEqual(target.execute('SELECT count(*) FROM rsi_policy_trials').fetchone()[0], 3)
                self.assertEqual(target.execute('PRAGMA foreign_key_check').fetchall(), [])
            finally:
                target.close()

    def test_failed_terminal_write_preserves_durable_proposal(self):
        with tempfile.TemporaryDirectory() as directory:
            db=baseline(str(pathlib.Path(directory)/'rsi.db'))
            try:
                migrate(db); migrate6(db)
                with db:
                    trial(db)
                pages=db.execute('PRAGMA page_count').fetchone()[0]
                db.execute(f'PRAGMA max_page_count={pages}')
                with self.assertRaises(sqlite3.OperationalError) as failure, db:
                    trial(db, 'REJECTED', payload='x'*16000)
                self.assertEqual(failure.exception.sqlite_errorcode, sqlite3.SQLITE_FULL)
                self.assertEqual(db.execute('SELECT stage FROM rsi_policy_trials').fetchall(), [('PROPOSED',)])
                db.execute(f'PRAGMA max_page_count={pages+20}')
                with db:
                    trial(db, 'CANCELLED')
                self.assertEqual(db.execute('SELECT count(*) FROM rsi_policy_trials').fetchone()[0], 2)
            finally:
                db.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
