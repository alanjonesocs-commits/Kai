#!/usr/bin/env python3
"""Synthetic-first, rebuildable NIA15 memory retrieval index.

This module runs inside Debian 12. It never connects to Android or writes to
canonical memory. Input snapshots must be explicitly supplied by an operator.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Any

FORMAT = "nia-memory-snapshot"
SCHEMA_VERSION = "1.0.0"
STATUSES = {"CONFIRMED", "REPORTED", "ASSUMED", "UNKNOWN", "NEEDS_VERIFICATION"}
CATEGORIES = {"GENERAL", "LEARNING", "ERROR", "PREFERENCE"}
REQUIRED_RECORD_FIELDS = {
    "key", "content", "createdAt", "updatedAt", "category", "hitCount",
    "source", "id", "receiptStatus", "claimStatus", "factStatus",
    "resolutionStatus", "sourceConversationId", "provenance",
}
REQUIRED_PROVENANCE_FIELDS = {"source", "conversationId", "observedAt"}


class SnapshotError(ValueError):
    pass


def canonical_json(value: Any) -> bytes:
    """Deterministic UTF-8 JSON bytes used for integrity hashing."""
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def records_digest(records: list[dict[str, Any]]) -> str:
    return hashlib.sha256(canonical_json(records)).hexdigest()


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def validate_snapshot(document: Any) -> dict[str, Any]:
    """Validate an envelope without mutating or repairing its contents."""
    errors: list[str] = []
    if not isinstance(document, dict):
        raise SnapshotError("snapshot root must be an object")
    if document.get("format") != FORMAT:
        errors.append("unsupported or missing format")
    if document.get("schema_version") != SCHEMA_VERSION:
        errors.append("unsupported schema_version")
    sid = document.get("snapshot_id")
    if not isinstance(sid, str) or not sid.strip():
        errors.append("snapshot_id must be a non-empty string")
    created = document.get("created_at_epoch_ms")
    if not _is_int(created) or created < 0:
        errors.append("created_at_epoch_ms must be a non-negative integer")
    source = document.get("source")
    if not isinstance(source, dict) or not isinstance(source.get("system"), str):
        errors.append("source.system is required")
    if document.get("hash_algorithm") != "SHA-256":
        errors.append("hash_algorithm must be SHA-256")
    records = document.get("records")
    if not isinstance(records, list):
        errors.append("records must be an array")
        records = []
    count = document.get("record_count")
    if not _is_int(count) or count != len(records):
        errors.append("record_count does not match records length")

    seen_ids: set[str] = set()
    for index, record in enumerate(records):
        prefix = f"records[{index}]"
        if not isinstance(record, dict):
            errors.append(f"{prefix} must be an object")
            continue
        missing = REQUIRED_RECORD_FIELDS - record.keys()
        if missing:
            errors.append(f"{prefix} missing fields: {','.join(sorted(missing))}")
            continue
        for field in ("key", "content", "id"):
            if not isinstance(record[field], str) or not record[field].strip():
                errors.append(f"{prefix}.{field} must be a non-empty string")
        rid = record.get("id")
        if isinstance(rid, str):
            if rid in seen_ids:
                errors.append(f"duplicate record id: {rid}")
            seen_ids.add(rid)
        for field in ("createdAt", "updatedAt"):
            if not _is_int(record[field]) or record[field] < 0:
                errors.append(f"{prefix}.{field} must be a non-negative integer")
        if not _is_int(record["hitCount"]) or record["hitCount"] < 0:
            errors.append(f"{prefix}.hitCount must be a non-negative integer")
        if record.get("category") not in CATEGORIES:
            errors.append(f"{prefix}.category is invalid")
        for field in ("receiptStatus", "claimStatus", "factStatus", "resolutionStatus"):
            if record.get(field) not in STATUSES:
                errors.append(f"{prefix}.{field} is invalid")
        if record.get("source") is not None and not isinstance(record["source"], str):
            errors.append(f"{prefix}.source must be string or null")
        if record.get("sourceConversationId") is not None and not isinstance(record["sourceConversationId"], str):
            errors.append(f"{prefix}.sourceConversationId must be string or null")
        provenance = record.get("provenance")
        if not isinstance(provenance, list):
            errors.append(f"{prefix}.provenance must be an array")
        else:
            for p_index, item in enumerate(provenance):
                pfx = f"{prefix}.provenance[{p_index}]"
                if not isinstance(item, dict) or not REQUIRED_PROVENANCE_FIELDS.issubset(item):
                    errors.append(f"{pfx} missing required provenance fields")
                    continue
                if item["source"] is not None and not isinstance(item["source"], str):
                    errors.append(f"{pfx}.source must be string or null")
                if item["conversationId"] is not None and not isinstance(item["conversationId"], str):
                    errors.append(f"{pfx}.conversationId must be string or null")
                if not _is_int(item["observedAt"]) or item["observedAt"] < 0:
                    errors.append(f"{pfx}.observedAt must be a non-negative integer")
    expected_hash = document.get("records_hash")
    if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
        errors.append("records_hash must be a lowercase SHA-256 hex digest")
    elif expected_hash != records_digest(records):
        errors.append("records_hash mismatch")
    if errors:
        raise SnapshotError("; ".join(errors))
    return document


def load_snapshot(path: Path, max_bytes: int = 64 * 1024 * 1024) -> dict[str, Any]:
    size = path.stat().st_size
    if size <= 0 or size > max_bytes:
        raise SnapshotError(f"snapshot size outside allowed range: {size}")
    raw = path.read_bytes()
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SnapshotError(f"invalid UTF-8 JSON: {exc}") from exc
    return validate_snapshot(document)


def _safe_generation_name(snapshot_id: str, digest: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", snapshot_id)[:80]
    if not safe:
        raise SnapshotError("snapshot_id cannot form a safe generation name")
    return f"{safe}-{digest[:12]}"


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _read_current(root: Path) -> tuple[str | None, dict[str, Any] | None]:
    pointer = root / "CURRENT"
    if not pointer.exists():
        return None, None
    try:
        data = json.loads(pointer.read_text(encoding="utf-8"))
        name = data["generation"]
        if not isinstance(name, str) or "/" in name or name in {"", ".", ".."}:
            raise ValueError("unsafe generation pointer")
        db_path = root / "generations" / name / "memory.sqlite"
        if not db_path.is_file():
            raise ValueError("active generation database is missing")
        with sqlite3.connect(db_path) as db:
            meta = dict(db.execute("SELECT key, value FROM index_metadata"))
        return name, meta
    except Exception as exc:
        raise SnapshotError(f"CURRENT pointer or active index is invalid: {exc}") from exc


def _create_index(db_path: Path, document: dict[str, Any]) -> None:
    db = sqlite3.connect(db_path)
    try:
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA synchronous=FULL")
        db.executescript("""
            CREATE TABLE index_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE memory_records (
                record_id TEXT PRIMARY KEY,
                memory_key TEXT NOT NULL,
                original_timestamp INTEGER NOT NULL,
                status_json TEXT NOT NULL,
                provenance_json TEXT NOT NULL,
                raw_record_json TEXT NOT NULL
            );
            CREATE VIRTUAL TABLE memory_search USING fts5(
                record_id UNINDEXED, memory_key, content, category,
                tokenize='unicode61'
            );
            CREATE INDEX memory_records_key_idx ON memory_records(memory_key);
        """)
        meta = {
            "schema_version": "1",
            "snapshot_id": document["snapshot_id"],
            "records_hash": document["records_hash"],
            "created_at_epoch_ms": str(document["created_at_epoch_ms"]),
            "record_count": str(document["record_count"]),
            "source_system": document["source"]["system"],
            "source_generation": str(document.get("source_generation", "")),
        }
        with db:
            db.executemany("INSERT INTO index_metadata(key,value) VALUES(?,?)", meta.items())
            for record in document["records"]:
                statuses = {
                    key: record[key] for key in (
                        "receiptStatus", "claimStatus", "factStatus", "resolutionStatus"
                    )
                }
                raw = json.dumps(record, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
                db.execute(
                    "INSERT INTO memory_records VALUES(?,?,?,?,?,?)",
                    (
                        record["id"], record["key"], record["createdAt"],
                        json.dumps(statuses, sort_keys=True),
                        json.dumps(record["provenance"], ensure_ascii=False, separators=(",", ":")),
                        raw,
                    ),
                )
                db.execute(
                    "INSERT INTO memory_search(record_id,memory_key,content,category) VALUES(?,?,?,?)",
                    (record["id"], record["key"], record["content"], record["category"]),
                )
        check = db.execute("PRAGMA integrity_check").fetchone()[0]
        if check != "ok":
            raise SnapshotError(f"SQLite integrity_check failed: {check}")
    finally:
        db.close()


def index_snapshot(
    document: dict[str, Any],
    root: Path,
    *,
    accept_uncertain_order: bool = False,
) -> dict[str, Any]:
    """Build a new generation and atomically switch CURRENT only after verification."""
    validate_snapshot(document)
    root.mkdir(parents=True, exist_ok=True)
    generations = root / "generations"
    generations.mkdir(exist_ok=True)
    lock_path = root / ".index.lock"
    with lock_path.open("a+b") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            active_name, active = _read_current(root)
            incoming_id = document["snapshot_id"]
            incoming_hash = document["records_hash"]
            if active:
                if active.get("snapshot_id") == incoming_id:
                    if active.get("records_hash") == incoming_hash:
                        return {"decision": "ALREADY_INDEXED", "generation": active_name}
                    raise SnapshotError("same snapshot_id supplied with a different records_hash")
                if active.get("records_hash") == incoming_hash:
                    return {"decision": "IDENTICAL_CONTENT", "generation": active_name}
                old_gen = active.get("source_generation", "")
                new_gen = str(document.get("source_generation", ""))
                if old_gen and new_gen:
                    if not new_gen.isdigit() or not old_gen.isdigit():
                        raise SnapshotError("source_generation values must be integers")
                    if int(new_gen) <= int(old_gen):
                        raise SnapshotError("incoming snapshot is not a newer source generation")
                else:
                    old_time = int(active.get("created_at_epoch_ms", "0"))
                    new_time = int(document["created_at_epoch_ms"])
                    if new_time < old_time and not accept_uncertain_order:
                        return {
                            "decision": "NEEDS_VERIFICATION",
                            "reason": "incoming timestamp is older and no source generation is available",
                            "generation": active_name,
                        }
                    if not accept_uncertain_order:
                        return {
                            "decision": "NEEDS_VERIFICATION",
                            "reason": "content differs but no trustworthy source generation is available",
                            "generation": active_name,
                        }

            generation = _safe_generation_name(incoming_id, incoming_hash)
            final_dir = generations / generation
            if final_dir.exists():
                raise SnapshotError("generation directory already exists but is not active")
            temp_dir = Path(tempfile.mkdtemp(prefix=".building-", dir=generations))
            try:
                db_path = temp_dir / "memory.sqlite"
                _create_index(db_path, document)
                with db_path.open("rb+") as handle:
                    os.fsync(handle.fileno())
                _fsync_dir(temp_dir)
                os.replace(temp_dir, final_dir)
                _fsync_dir(generations)
                pointer_tmp = root / ".CURRENT.tmp"
                pointer_tmp.write_text(
                    json.dumps({"generation": generation}, separators=(",", ":")),
                    encoding="utf-8",
                )
                with pointer_tmp.open("rb+") as handle:
                    os.fsync(handle.fileno())
                os.replace(pointer_tmp, root / "CURRENT")
                _fsync_dir(root)
                return {"decision": "INDEXED", "generation": generation, "previous_generation": active_name}
            except Exception:
                if temp_dir.exists():
                    shutil.rmtree(temp_dir, ignore_errors=True)
                raise
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def search_index(root: Path, query: str, limit: int = 20) -> list[dict[str, Any]]:
    if not query.strip():
        return []
    _, _meta = _read_current(root)
    current = json.loads((root / "CURRENT").read_text(encoding="utf-8"))["generation"]
    db_path = root / "generations" / current / "memory.sqlite"
    with sqlite3.connect(db_path) as db:
        rows = db.execute(
            """SELECT r.record_id, r.raw_record_json, bm25(memory_search)
               FROM memory_search
               JOIN memory_records r ON r.record_id = memory_search.record_id
               WHERE memory_search MATCH ?
               ORDER BY bm25(memory_search)
               LIMIT ?""",
            (query, max(1, min(limit, 100))),
        ).fetchall()
    return [
        {"record_id": row[0], "raw_record": json.loads(row[1]), "rank": row[2]}
        for row in rows
    ]


def status_index(root: Path) -> dict[str, Any]:
    name, meta = _read_current(root)
    return {"generation": name, "metadata": meta}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    validate_cmd = sub.add_parser("validate")
    validate_cmd.add_argument("snapshot", type=Path)
    index_cmd = sub.add_parser("index")
    index_cmd.add_argument("snapshot", type=Path)
    index_cmd.add_argument("--root", type=Path, required=True)
    index_cmd.add_argument("--accept-uncertain-order", action="store_true")
    search_cmd = sub.add_parser("search")
    search_cmd.add_argument("--root", type=Path, required=True)
    search_cmd.add_argument("query")
    status_cmd = sub.add_parser("status")
    status_cmd.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            doc = load_snapshot(args.snapshot)
            print(json.dumps({"valid": True, "snapshot_id": doc["snapshot_id"], "record_count": doc["record_count"], "records_hash": doc["records_hash"]}, indent=2))
        elif args.command == "index":
            doc = load_snapshot(args.snapshot)
            print(json.dumps(index_snapshot(doc, args.root, accept_uncertain_order=args.accept_uncertain_order), indent=2))
        elif args.command == "search":
            print(json.dumps(search_index(args.root, args.query), ensure_ascii=False, indent=2))
        elif args.command == "status":
            print(json.dumps(status_index(args.root), indent=2))
        return 0
    except Exception as exc:
        print(json.dumps({"error": str(exc), "status": "REJECTED"}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
