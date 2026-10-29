#!/usr/bin/env python3
"""Prepare a controlled backup and check exports from an uninstrumented smoke app.

This tool only reads/writes host files. Import/export the archives through the
normal app UI on a fresh, separately identified disposable release installation.
It never connects to Android, reads private app data, or deserializes Java settings.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import uuid
import zipfile


COLUMNS = ("uid", "service_id", "url", "name", "avatar_url", "subscriber_count",
           "description", "notification_mode")
REPOSITORY = Path(__file__).resolve().parents[1]
SCHEMA = REPOSITORY / "app/schemas/org.schabi.newpipe.database.AppDatabase/9.json"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def prepare(output):
    schema = json.loads(SCHEMA.read_text())["database"]
    require(schema["version"] == 9, "Expected exported Room schema 9")
    output.mkdir(parents=True, exist_ok=False)
    nonce = uuid.uuid4().hex
    rows = []
    for index, service in enumerate((0, 0, 1, 2, 3, 4, 99, 2147483647)):
        rows.append(dict(zip(COLUMNS, (101 + index, service,
            f"https://fixture.invalid/release/{nonce}/{index}",
            f"Release fixture {index} service {service}", "", 100 + index,
            f"Controlled release metadata {nonce}/{index}", 0))))
    preferences = {
        "saved_tabs_key": json.dumps({"tabs": [{"tab_id": 1}]}, separators=(",", ":")),
        "list_view_mode": "list",
        "disable_error_reports": True,
    }
    database = output / "newpipe.db"
    with sqlite3.connect(database) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        for entity in schema["entities"]:
            table = entity["tableName"]
            connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity["indices"]:
                connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            connection.execute(query)
        connection.execute("PRAGMA user_version=9")
        connection.executemany("INSERT INTO subscriptions (" + ",".join(COLUMNS) +
                               ") VALUES (?,?,?,?,?,?,?,?)", [tuple(row[c] for c in COLUMNS) for row in rows])
        require(connection.execute("PRAGMA quick_check").fetchone() == ("ok",), "Fixture integrity")
        require(not connection.execute("PRAGMA foreign_key_check").fetchall(), "Fixture foreign keys")
    archive = output / "release-subscriptions-seed.zip"
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as backup:
        backup.write(database, "newpipe.db")
        backup.writestr("preferences.json", json.dumps(preferences))
    expected = dict(nonce=nonce, schema=str(SCHEMA.relative_to(REPOSITORY)), version=9,
                    identity_hash=schema["identityHash"], rows=rows, preferences=preferences,
                    seed_sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
    output.joinpath("expected.json").write_text(json.dumps(expected, indent=2) + "\n")
    print(json.dumps(dict(archive=str(archive), expected=str(output / "expected.json"),
                         records=len(rows), service_ids=sorted({r["service_id"] for r in rows})), indent=2))


def check_export(archive, expected_file, removed_service, report):
    expected = json.loads(expected_file.read_text())
    require(removed_service is None or any(
        row["service_id"] == removed_service for row in expected["rows"]),
        "Removed service is absent from seed")
    wanted = [r for r in expected["rows"] if r["service_id"] != removed_service]
    with zipfile.ZipFile(archive) as backup:
        require(backup.testzip() is None, "Backup CRC failure")
        require(backup.namelist().count("newpipe.db") == 1, "Expected one database entry")
        require(backup.getinfo("newpipe.db").file_size <= 20 * 1024 * 1024, "Unexpected database size")
        contents = backup.read("newpipe.db")
        preferences = json.loads(backup.read("preferences.json"))
    with tempfile.TemporaryDirectory(prefix="pvcpipe-release-export-") as directory:
        path = Path(directory) / "newpipe.db"
        path.write_bytes(contents)
        with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as connection:
            require(connection.execute("PRAGMA quick_check").fetchone() == ("ok",), "Export integrity")
            require(not connection.execute("PRAGMA foreign_key_check").fetchall(), "Export foreign keys")
            require(connection.execute("PRAGMA user_version").fetchone() == (expected["version"],), "Schema version changed")
            require(connection.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone() == (expected["identity_hash"],), "Unexpected Room identity")
            rows = [dict(zip(COLUMNS, row)) for row in connection.execute(
                "SELECT " + ",".join(COLUMNS) + " FROM subscriptions ORDER BY uid")]
            require(rows == wanted, "Subscription IDs/metadata differ from expected fixture")
            other_counts = {}
            for entity in json.loads(SCHEMA.read_text())["database"]["entities"]:
                table = entity["tableName"]
                if table == "subscriptions":
                    continue
                count = connection.execute('SELECT COUNT(*) FROM "' + table + '"').fetchone()[0]
                require(count == 0, "Unexpected records in " + table)
                other_counts[table] = count
    tabs = json.loads(preferences.get("saved_tabs_key", "null"))
    require(tabs == {"tabs": [{"tab_id": 1}]}, "Expected subscriptions-only saved tabs")
    result = dict(passed=True, archive=str(archive), archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                  removed_service=removed_service, rows=rows, other_counts=other_counts,
                  ui_mode=preferences.get("list_view_mode"), saved_tabs=tabs)
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(dict(passed=True, records=len(rows), service_ids=sorted({r["service_id"] for r in rows}),
                         report=str(report)), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--output", type=Path, required=True)
    check_parser = commands.add_parser("check-export")
    check_parser.add_argument("--archive", type=Path, required=True)
    check_parser.add_argument("--expected", type=Path, required=True)
    check_parser.add_argument("--removed-service", type=int)
    check_parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.output)
    else:
        check_export(args.archive, args.expected, args.removed_service, args.report)


if __name__ == "__main__":
    main()
