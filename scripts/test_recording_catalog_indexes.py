#!/usr/bin/env python3
"""Exercise source-derived catalog DDL/order on host SQLite, without Android/Gradle.

This narrow fixture fails closed when the small SQL-producing Kotlin expressions
change shape. It is a query-plan/storage regression, not native SQLite or latency
qualification. All generated databases live in a temporary project-local directory.
"""
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/app/smallthingz/reverb/RecordingDatabase.kt"
TEXT = SOURCE.read_text()
CONSTANTS = dict(re.findall(r'internal const val (\w+) = "([^"]*)"', TEXT))
VERSION = int(re.search(r"internal const val DATABASE_VERSION = (\d+)", TEXT)[1])


def section(start, end):
    return TEXT.split(start, 1)[1].split(end, 1)[0]


ORDER_EXPRESSION = section(
    "private fun recordingCatalogDescendingIntegerOrder(column: String): String =",
    "\n\n",
).strip()


def interpolate(value, variables):
    return re.sub(
        r"\$\{RecordingDatabase\.(\w+)\}|\$(\w+)",
        lambda match: CONSTANTS[match[1]] if match[1] else variables[match[2]],
        value,
    )


def concat_sql(expression, variables=None):
    """Evaluate only the literal/constant concatenation used by the real source."""
    variables = variables or {}
    token = re.compile(
        r'\s*("(?:[^"\\]|\\.)*"|RecordingDatabase\.\w+|'
        r"recordingCatalogDescendingIntegerOrder\(RecordingDatabase\.\w+\)|\w+)\s*(\+|$)"
    )
    values = []
    position = 0
    expression = expression.strip().removesuffix(",").strip()
    while position < len(expression):
        match = token.match(expression, position)
        if match is None:
            raise AssertionError(f"Unsupported SQL source expression: {expression[position:]}")
        value = match[1]
        if value.startswith('"'):
            values.append(interpolate(json.loads(value), variables))
        elif value.startswith("RecordingDatabase."):
            values.append(CONSTANTS[value.split(".")[1]])
        elif value.startswith("recordingCatalogDescendingIntegerOrder("):
            column = value.split(".")[1].removesuffix(")")
            values.append(concat_sql(ORDER_EXPRESSION, {"column": CONSTANTS[column]}))
        else:
            values.append(variables[value])
        position = match.end()
    return "".join(values)


ORDER = concat_sql(section("internal fun recordingCatalogOrderBy(): String =", "\n\n"))
INDEX_BODY = section("private fun SQLiteDatabase.createIndexes() {", "\ninternal enum class")
assert "val orderBy = recordingCatalogOrderBy()\n    transaction {" in INDEX_BODY
INDEX_EXPRESSIONS = re.findall(
    r'execSQL\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+),?\s*\)',
    INDEX_BODY,
)
INDEX_SQL = [concat_sql(expression, {"orderBy": ORDER}) for expression in INDEX_EXPRESSIONS]
assert len(INDEX_SQL) == INDEX_BODY.count("execSQL(") == 4
SCHEMA = interpolate(
    re.search(r'"""\s*(CREATE TABLE.*?)[ \t]*"""', TEXT, re.S)[1].strip(),
    {},
)
LEGACY_SQL = [
    "CREATE INDEX recordings_started_created_idx ON recordings "
    "(startedAtMillis DESC, createdAtMillis DESC)",
    "CREATE INDEX recordings_directory_started_created_idx ON recordings "
    "(directoryId, startedAtMillis DESC, createdAtMillis DESC)",
]
NEW_NAMES = {"recordings_catalog_order_idx", "recordings_directory_catalog_order_idx"}
OLD_NAMES = {"recordings_started_created_idx", "recordings_directory_started_created_idx"}
PLANS = {}


def install(connection, fail_after=None):
    # SQLiteOpenHelper onOpen's production createIndexes is one Android transaction.
    # The host equivalent also rolls back the complete DDL replacement on failure.
    with connection:
        connection.execute("BEGIN")
        for number, statement in enumerate(INDEX_SQL):
            connection.execute(statement)
            if number == fail_after:
                raise RuntimeError("injected DDL failure")


def snapshot(connection):
    return connection.execute("SELECT rowid, * FROM recordings ORDER BY rowid").fetchall()


def indexes(connection):
    return {
        row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='recordings'"
        )
    }


def populate(connection):
    columns = connection.execute("PRAGMA table_info(recordings)").fetchall()
    rows = [
        ("recent", "a", 900, 910),
        ("tie-first", "a", 700, 710),
        ("tie-second", "a", 700, 710),
        ("other-directory", "b", 800, 810),
        ("zero", "b", 0, 0),
        ("bad-start-text", "a", "malformed", 500),
        ("bad-start-real", "a", 0.5, 400),
        ("bad-start-blob", "b", b"\x01\xff", 300),
        ("bad-created-text", "a", 600, "malformed"),
        ("bad-created-blob", "a", 500, b"\x00\x01"),
        ("negative-start", "b", -2, 100),
    ]
    for identity, directory, started, created in rows:
        values = {
            column[1]: (0 if column[2] == "INTEGER" else "")
            for column in columns
        }
        values.update(
            id=f"/recordings/{directory}/{identity}.wav", directoryId=f"/recordings/{directory}",
            displayName=identity + ".wav", storageTypeCode=1,
            mimeType="audio/wav", startedAtMillis=started, createdAtMillis=created,
            lastSeenAtMillis=1, missingSinceMillis=None,
        )
        connection.execute(
            "INSERT INTO recordings (" + ", ".join(values) + ") VALUES (" +
            ", ".join("?" for _ in values) + ")", tuple(values.values()),
        )
    connection.commit()


def timestamp_valid_ids(connection, where=""):
    # Only timestamp validity is varied in this fixture. Production rejects wrong
    # types/negative timestamps; raw tie order among rejected rows is not a contract.
    rows = connection.execute(
        "SELECT id, startedAtMillis, createdAtMillis FROM recordings " +
        where + " ORDER BY " + ORDER
    ).fetchall()
    return [
        row[0] for row in rows
        if type(row[1]) is int and row[1] >= 0 and type(row[2]) is int and row[2] >= 0
    ]


class RecordingCatalogIndexTest(unittest.TestCase):
    def setUp(self):
        temporary_root = ROOT / ".tmp"
        temporary_root.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="catalog-index-", dir=temporary_root)
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "catalog.db"
        self.connection = sqlite3.connect(self.path)
        self.addCleanup(lambda: self.connection.close())
        self.connection.execute(SCHEMA)
        self.connection.execute(f"PRAGMA user_version = {VERSION}")
        populate(self.connection)

    def plans_have_no_sort(self):
        for label, where in (
            ("all", ""),
            ("directory", "WHERE directoryId = '/recordings/a'"),
            ("outside", "WHERE directoryId NOT IN ('/recordings/b')"),
        ):
            # The real queries project more bounded columns; this uses both
            # timestamp projection aliases as well as non-index-covered columns.
            projection = (
                "id, displayName, waveformData, "
                "CASE WHEN typeof(startedAtMillis) = 'integer' THEN startedAtMillis "
                "ELSE -1 END AS startedAtMillis, "
                "CASE WHEN typeof(createdAtMillis) = 'integer' THEN createdAtMillis "
                "ELSE -1 END AS createdAtMillis"
            )
            plan = [
                row[3] for row in self.connection.execute(
                    "EXPLAIN QUERY PLAN SELECT " + projection +
                    " FROM recordings " + where + " ORDER BY " + ORDER
                )
            ]
            PLANS[label] = plan
            self.assertFalse(any("TEMP B-TREE" in step for step in plan), plan)
            self.assertTrue(any("catalog_order_idx" in step for step in plan), plan)

    def test_existing_database_preserves_rows_order_and_version_on_reopen(self):
        for statement in LEGACY_SQL:
            self.connection.execute(statement)
        before = snapshot(self.connection)
        expected = {
            where: timestamp_valid_ids(self.connection, where)
            for where in ("", "WHERE directoryId = '/recordings/a'", "WHERE directoryId NOT IN ('/recordings/b')")
        }
        self.assertEqual([
            "/recordings/a/recent.wav", "/recordings/b/other-directory.wav",
            "/recordings/a/tie-first.wav", "/recordings/a/tie-second.wav", "/recordings/b/zero.wav",
        ], expected[""])
        install(self.connection)
        self.assertEqual(before, snapshot(self.connection))
        self.assertEqual(VERSION, self.connection.execute("PRAGMA user_version").fetchone()[0])
        self.assertTrue(NEW_NAMES <= indexes(self.connection))
        self.assertFalse(OLD_NAMES & indexes(self.connection))
        for where, ids in expected.items():
            self.assertEqual(ids, timestamp_valid_ids(self.connection, where))
        self.plans_have_no_sort()
        schema_version = self.connection.execute("PRAGMA schema_version").fetchone()[0]
        self.connection.close()
        self.connection = sqlite3.connect(self.path)
        install(self.connection)
        self.assertEqual(schema_version, self.connection.execute("PRAGMA schema_version").fetchone()[0])
        self.assertEqual(before, snapshot(self.connection))
        for where, ids in expected.items():
            self.assertEqual(ids, timestamp_valid_ids(self.connection, where))
        self.plans_have_no_sort()
        self.assertEqual([("ok",)], self.connection.execute("PRAGMA integrity_check").fetchall())

    def test_fresh_database_and_repeated_open_are_idempotent(self):
        before = snapshot(self.connection)
        install(self.connection)
        schema_version = self.connection.execute("PRAGMA schema_version").fetchone()[0]
        install(self.connection)
        self.assertEqual(schema_version, self.connection.execute("PRAGMA schema_version").fetchone()[0])
        self.assertEqual(before, snapshot(self.connection))
        self.assertTrue(NEW_NAMES <= indexes(self.connection))
        self.plans_have_no_sort()

    def test_failure_rolls_back_replacements_and_drops(self):
        for statement in LEGACY_SQL:
            self.connection.execute(statement)
        before = snapshot(self.connection)
        old_indexes = indexes(self.connection)
        for fail_after in range(len(INDEX_SQL)):
            with self.assertRaisesRegex(RuntimeError, "injected DDL failure"):
                install(self.connection, fail_after=fail_after)
            self.assertEqual(old_indexes, indexes(self.connection))
            self.assertEqual(before, snapshot(self.connection))
        install(self.connection)
        self.plans_have_no_sort()


if __name__ == "__main__":
    result = unittest.main(exit=False, verbosity=2).result
    print(json.dumps({
        "sqlite": sqlite3.sqlite_version,
        "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "source_sql": INDEX_SQL,
        "plans": PLANS,
        "native_qualification": "not run",
    }, indent=2))
    raise SystemExit(not result.wasSuccessful())
