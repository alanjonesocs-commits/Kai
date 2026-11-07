#!/usr/bin/env python3
"""NIA15 schema 1.1.0 strict snapshot verifier and isolated SQLite index.

Synthetic-first and deliberately disconnected from Android. No transfer,
discovery, network access, or canonical-memory write-back is implemented.
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
import stat
import sys
import tempfile
from urllib.parse import quote
from pathlib import Path
from typing import Any

VERSION = "1.1.0"
HASH_SCOPE = "snapshot-envelope-v1"
MAX_BYTES = 32 * 1024 * 1024  # Provisional until Pixel 9 heap measurements.
MAX_DEPTH = 32
I64_MIN, I64_MAX = -(2**63), 2**63 - 1
ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
HEX_RE = re.compile(r"^[0-9a-f]{64}$")
STATUSES = {"CONFIRMED", "REPORTED", "ASSUMED", "UNKNOWN", "NEEDS_VERIFICATION"}
CATEGORIES = {"GENERAL", "LEARNING", "ERROR", "PREFERENCE"}
ENVELOPE_FIELDS = {
    "schema_version", "snapshot_id", "created_at_epoch_ms", "source",
    "source_generation", "generation_authority", "record_count", "records",
    "records_hash", "hash_scope", "envelope_hash",
}
SOURCE_FIELDS = {"application", "platform", "device_label", "system"}
RECORD_FIELDS = {
    "key", "content", "createdAt", "updatedAt", "category", "hitCount",
    "source", "id", "receiptStatus", "claimStatus", "factStatus",
    "resolutionStatus", "sourceConversationId", "provenance",
}
PROVENANCE_FIELDS = {"source", "conversationId", "observedAt"}


class SnapshotError(ValueError):
    pass


def _parse_int(token: str) -> int:
    if token == "-0":
        raise SnapshotError("INVALID_NUMBER")
    if len(token) > 20:
        raise SnapshotError("INTEGER_OUT_OF_RANGE")
    value = int(token)
    if value < I64_MIN or value > I64_MAX:
        raise SnapshotError("INTEGER_OUT_OF_RANGE")
    return value


def _reject_number(_token: str) -> None:
    raise SnapshotError("UNSUPPORTED_DECIMAL")


def _pairs_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SnapshotError("DUPLICATE_OBJECT_KEY")
        result[key] = value
    return result


def _check_tree(value: Any, container_depth: int = 0) -> None:
    """Count nested JSON containers, not scalar values or object member names."""
    if isinstance(value, str):
        try:
            value.encode("utf-8", errors="strict")
        except UnicodeEncodeError as exc:
            raise SnapshotError("LONE_SURROGATE") from exc
    elif isinstance(value, bool) or value is None:
        return
    elif isinstance(value, int):
        if value < I64_MIN or value > I64_MAX:
            raise SnapshotError("INTEGER_OUT_OF_RANGE")
    elif isinstance(value, float):
        raise SnapshotError("UNSUPPORTED_DECIMAL")
    elif isinstance(value, list):
        next_depth = container_depth + 1
        if next_depth > MAX_DEPTH:
            raise SnapshotError("JSON_DEPTH_EXCEEDED")
        for item in value:
            _check_tree(item, next_depth)
    elif isinstance(value, dict):
        next_depth = container_depth + 1
        if next_depth > MAX_DEPTH:
            raise SnapshotError("JSON_DEPTH_EXCEEDED")
        for key, item in value.items():
            if not isinstance(key, str):
                raise SnapshotError("INVALID_OBJECT_KEY")
            _check_tree(key, container_depth)
            _check_tree(item, next_depth)
    else:
        raise SnapshotError("INVALID_JSON_VALUE")


def _quote(value: str) -> str:
    _check_tree(value)
    out = ['"']
    short = {8: r"\b", 9: r"\t", 10: r"\n", 12: r"\f", 13: r"\r", 34: r'\"', 92: r"\\"}
    for char in value:
        code = ord(char)
        if code in short:
            out.append(short[code])
        elif code <= 0x1F:
            out.append(f"\\u{code:04x}")
        else:
            out.append(char)
    out.append('"')
    return "".join(out)


def canonical_json(value: Any) -> bytes:
    """NIA-CJ1: UTF-8, compact, UTF-16-code-unit key ordering, strict values."""
    _check_tree(value)

    def emit(item: Any) -> str:
        if item is None:
            return "null"
        if item is True:
            return "true"
        if item is False:
            return "false"
        if isinstance(item, int) and not isinstance(item, bool):
            return str(item)
        if isinstance(item, str):
            return _quote(item)
        if isinstance(item, list):
            return "[" + ",".join(emit(child) for child in item) + "]"
        if isinstance(item, dict):
            keys = sorted(item, key=lambda key: key.encode("utf-16-be"))
            return "{" + ",".join(_quote(key) + ":" + emit(item[key]) for key in keys) + "}"
        raise SnapshotError("INVALID_JSON_VALUE")

    return emit(value).encode("utf-8")


def parse_snapshot_bytes(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_BYTES:
        raise SnapshotError("SNAPSHOT_SIZE_INVALID")
    if raw.startswith(b"\xef\xbb\xbf"):
        raise SnapshotError("UTF8_BOM_FORBIDDEN")
    try:
        text = raw.decode("utf-8", errors="strict")
        doc = json.loads(
            text, object_pairs_hook=_pairs_no_duplicates,
            parse_int=_parse_int, parse_float=_reject_number,
            parse_constant=_reject_number,
        )
    except SnapshotError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise SnapshotError("INVALID_JSON") from exc
    if not isinstance(doc, dict):
        raise SnapshotError("ROOT_NOT_OBJECT")
    _check_tree(doc)
    validate_snapshot(doc)
    return doc


def records_digest(records: list[dict[str, Any]]) -> str:
    return hashlib.sha256(canonical_json(records)).hexdigest()


def envelope_digest(doc: dict[str, Any]) -> str:
    unsigned = {key: value for key, value in doc.items() if key != "envelope_hash"}
    return hashlib.sha256(canonical_json(unsigned)).hexdigest()


def _i64(value: Any, *, nonnegative: bool = False) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and I64_MIN <= value <= I64_MAX and (not nonnegative or value >= 0)


def validate_snapshot(doc: Any) -> dict[str, Any]:
    if not isinstance(doc, dict):
        raise SnapshotError("ROOT_NOT_OBJECT")
    keys = set(doc)
    if keys != ENVELOPE_FIELDS:
        raise SnapshotError("ENVELOPE_FIELDS_INVALID")
    if doc["schema_version"] != VERSION:
        raise SnapshotError("SCHEMA_VERSION_UNSUPPORTED")
    sid = doc["snapshot_id"]
    if not isinstance(sid, str) or not ID_RE.fullmatch(sid):
        raise SnapshotError("SNAPSHOT_ID_INVALID")
    if not _i64(doc["created_at_epoch_ms"], nonnegative=True):
        raise SnapshotError("CREATED_AT_INVALID")
    if not _i64(doc["source_generation"], nonnegative=True) and doc["source_generation"] is not None:
        raise SnapshotError("SOURCE_GENERATION_INVALID")
    authority = doc["generation_authority"]
    if authority is not None and (not isinstance(authority, str) or not authority.strip()):
        raise SnapshotError("GENERATION_AUTHORITY_INVALID")
    source = doc["source"]
    if not isinstance(source, dict) or set(source) != SOURCE_FIELDS:
        raise SnapshotError("SOURCE_FIELDS_INVALID")
    for field in ("application", "platform", "system"):
        if not isinstance(source[field], str) or not source[field].strip():
            raise SnapshotError("SOURCE_VALUE_INVALID")
    if source["device_label"] is not None and not isinstance(source["device_label"], str):
        raise SnapshotError("SOURCE_VALUE_INVALID")
    records = doc["records"]
    if not isinstance(records, list) or not _i64(doc["record_count"], nonnegative=True) or doc["record_count"] != len(records):
        raise SnapshotError("RECORD_COUNT_MISMATCH")
    if doc["hash_scope"] != HASH_SCOPE:
        raise SnapshotError("HASH_SCOPE_INVALID")

    seen_ids: set[str] = set()
    for record in records:
        if not isinstance(record, dict) or not RECORD_FIELDS.issubset(record):
            raise SnapshotError("RECORD_FIELDS_INVALID")
        for field in ("key", "id"):
            if not isinstance(record[field], str) or not record[field].strip():
                raise SnapshotError("RECORD_ID_OR_KEY_INVALID")
        if record["id"] in seen_ids:
            raise SnapshotError("DUPLICATE_RECORD_ID")
        seen_ids.add(record["id"])
        if not isinstance(record["content"], str):
            raise SnapshotError("RECORD_CONTENT_INVALID")
        for field in ("createdAt", "updatedAt"):
            if not _i64(record[field], nonnegative=True):
                raise SnapshotError("RECORD_NUMBER_INVALID")
        # MemoryEntry.hitCount is Kotlin Int (32-bit), unlike the Long timestamps.
        hit_count = record["hitCount"]
        if not isinstance(hit_count, int) or isinstance(hit_count, bool) or not 0 <= hit_count <= 2**31 - 1:
            raise SnapshotError("RECORD_HIT_COUNT_INVALID")
        category = record["category"]
        if not isinstance(category, str) or category not in CATEGORIES:
            raise SnapshotError("RECORD_CATEGORY_INVALID")
        for field in ("receiptStatus", "claimStatus", "factStatus", "resolutionStatus"):
            status_value = record[field]
            if not isinstance(status_value, str) or status_value not in STATUSES:
                raise SnapshotError("RECORD_STATUS_INVALID")
        for field in ("source", "sourceConversationId"):
            if record[field] is not None and not isinstance(record[field], str):
                raise SnapshotError("RECORD_SOURCE_INVALID")
        provenance = record["provenance"]
        if not isinstance(provenance, list):
            raise SnapshotError("PROVENANCE_INVALID")
        for item in provenance:
            if not isinstance(item, dict) or not PROVENANCE_FIELDS.issubset(item):
                raise SnapshotError("PROVENANCE_FIELDS_INVALID")
            for field in ("source", "conversationId"):
                if item[field] is not None and not isinstance(item[field], str):
                    raise SnapshotError("PROVENANCE_SOURCE_INVALID")
            if not _i64(item["observedAt"], nonnegative=True):
                raise SnapshotError("PROVENANCE_TIMESTAMP_INVALID")

    if not isinstance(doc["records_hash"], str) or not HEX_RE.fullmatch(doc["records_hash"]):
        raise SnapshotError("RECORDS_HASH_INVALID")
    if not isinstance(doc["envelope_hash"], str) or not HEX_RE.fullmatch(doc["envelope_hash"]):
        raise SnapshotError("ENVELOPE_HASH_INVALID")
    if doc["records_hash"] != records_digest(records):
        raise SnapshotError("RECORDS_HASH_MISMATCH")
    if doc["envelope_hash"] != envelope_digest(doc):
        raise SnapshotError("ENVELOPE_HASH_MISMATCH")
    return doc


def read_snapshot_file(path: Path) -> tuple[bytes, dict[str, Any]]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise SnapshotError("SNAPSHOT_OPEN_FAILED") from exc
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size <= 0 or info.st_size > MAX_BYTES:
            raise SnapshotError("SNAPSHOT_FILE_INVALID")
        chunks, remaining = [], MAX_BYTES + 1
        while remaining:
            chunk = os.read(fd, min(65536, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        raw = b"".join(chunks)
        doc = parse_snapshot_bytes(raw)
        return raw, doc
    finally:
        os.close(fd)


def _private_dir(path: Path) -> None:
    if path.exists() or path.is_symlink():
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
            raise SnapshotError("INDEX_DIRECTORY_INVALID")
    else:
        path.mkdir(parents=True, mode=0o700)
    os.chmod(path, 0o700)


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _read_current(root: Path) -> tuple[str | None, dict[str, str] | None]:
    current = root / "CURRENT"
    if current.is_symlink():
        raise SnapshotError("RECOVERY_NEEDED")
    generations = root / "generations"
    if generations.exists() or generations.is_symlink():
        if generations.is_symlink() or not generations.is_dir():
            raise SnapshotError("RECOVERY_NEEDED")
    if not current.exists():
        if generations.exists() and any(generations.iterdir()):
            raise SnapshotError("RECOVERY_NEEDED")
        return None, None
    _private_dir(generations)
    if current.is_symlink() or not current.is_file():
        raise SnapshotError("RECOVERY_NEEDED")
    name = current.read_text("ascii").strip()
    if not HEX_RE.fullmatch(name):
        raise SnapshotError("RECOVERY_NEEDED")
    db_path = root / "generations" / name / "index.sqlite"
    if db_path.is_symlink() or not db_path.is_file():
        raise SnapshotError("RECOVERY_NEEDED")
    try:
        uri = "file:" + quote(str(db_path.resolve())) + "?mode=ro"
        with sqlite3.connect(uri, uri=True) as db:
            if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise SnapshotError("RECOVERY_NEEDED")
            meta = dict(db.execute("SELECT key,value FROM metadata"))
        if meta.get("envelope_hash") != name:
            raise SnapshotError("RECOVERY_NEEDED")
        return name, meta
    except (sqlite3.Error, OSError) as exc:
        raise SnapshotError("RECOVERY_NEEDED") from exc


def _write_synced(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        view = memoryview(data)
        while view:
            count = os.write(fd, view)
            if count <= 0:
                raise SnapshotError("FILE_WRITE_FAILED")
            view = view[count:]
        os.fsync(fd)
    finally:
        os.close(fd)


def _build_sqlite(db_path: Path, doc: dict[str, Any]) -> None:
    db = sqlite3.connect(db_path)
    os.chmod(db_path, 0o600)
    try:
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA foreign_keys=ON")
        db.executescript("""
            CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE records(id TEXT PRIMARY KEY, memory_key TEXT NOT NULL, content TEXT NOT NULL, raw_json TEXT NOT NULL);
            CREATE VIRTUAL TABLE search USING fts5(id UNINDEXED, memory_key, content, tokenize='unicode61');
        """)
        meta = {
            "schema_version": VERSION, "snapshot_id": doc["snapshot_id"],
            "records_hash": doc["records_hash"], "envelope_hash": doc["envelope_hash"],
            "created_at_epoch_ms": str(doc["created_at_epoch_ms"]),
            "record_count": str(doc["record_count"]),
            "generation_authority": doc["generation_authority"] or "",
            "source_generation": "" if doc["source_generation"] is None else str(doc["source_generation"]),
        }
        with db:
            db.executemany("INSERT INTO metadata VALUES(?,?)", meta.items())
            for record in doc["records"]:
                raw = canonical_json(record).decode("utf-8")
                db.execute("INSERT INTO records VALUES(?,?,?,?)", (record["id"], record["key"], record["content"], raw))
                db.execute("INSERT INTO search VALUES(?,?,?)", (record["id"], record["key"], record["content"]))
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise SnapshotError("SQLITE_INTEGRITY_FAILED")
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise SnapshotError("SQLITE_FOREIGN_KEY_FAILED")
        if db.execute("SELECT COUNT(*) FROM records").fetchone()[0] != doc["record_count"]:
            raise SnapshotError("SQLITE_RECORD_COUNT_MISMATCH")
    finally:
        db.close()



def _verify_existing_generation(generation_dir: Path, doc: dict[str, Any]) -> None:
    """Reuse only a complete immutable generation for this exact snapshot."""
    db_path = generation_dir / "index.sqlite"
    if generation_dir.is_symlink() or db_path.is_symlink() or not db_path.is_file():
        raise SnapshotError("GENERATION_EXISTING_INVALID")
    uri = "file:" + quote(str(db_path.resolve())) + "?mode=ro"
    try:
        with sqlite3.connect(uri, uri=True) as db:
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise SnapshotError("GENERATION_EXISTING_INVALID")
            meta = dict(db.execute("SELECT key,value FROM metadata"))
            expected = {
                "schema_version": VERSION,
                "snapshot_id": doc["snapshot_id"],
                "records_hash": doc["records_hash"],
                "envelope_hash": doc["envelope_hash"],
                "created_at_epoch_ms": str(doc["created_at_epoch_ms"]),
                "record_count": str(doc["record_count"]),
                "generation_authority": doc["generation_authority"] or "",
                "source_generation": "" if doc["source_generation"] is None else str(doc["source_generation"]),
            }
            if any(meta.get(key) != value for key, value in expected.items()):
                raise SnapshotError("GENERATION_EXISTING_INVALID")
            rows = db.execute("SELECT raw_json FROM records ORDER BY rowid").fetchall()
            indexed_records = []
            for (raw_record,) in rows:
                record = json.loads(
                    raw_record,
                    object_pairs_hook=_pairs_no_duplicates,
                    parse_int=_parse_int,
                    parse_float=_reject_number,
                    parse_constant=_reject_number,
                )
                if canonical_json(record).decode("utf-8") != raw_record:
                    raise SnapshotError("GENERATION_EXISTING_INVALID")
                indexed_records.append(record)
            if len(indexed_records) != doc["record_count"] or indexed_records != doc["records"]:
                raise SnapshotError("GENERATION_EXISTING_INVALID")
            if records_digest(indexed_records) != doc["records_hash"]:
                raise SnapshotError("GENERATION_EXISTING_INVALID")
            if db.execute("SELECT COUNT(*) FROM search").fetchone()[0] != doc["record_count"]:
                raise SnapshotError("GENERATION_EXISTING_INVALID")
    except SnapshotError:
        raise
    except (sqlite3.Error, OSError, ValueError, TypeError) as exc:
        raise SnapshotError("GENERATION_EXISTING_INVALID") from exc


def _active_decision(doc: dict[str, Any], active: dict[str, str] | None) -> str | None:
    if not active:
        return None
    if active["snapshot_id"] == doc["snapshot_id"]:
        if active["envelope_hash"] == doc["envelope_hash"]:
            return "ALREADY_INDEXED"
        raise SnapshotError("IDENTITY_CONFLICT")
    if active["records_hash"] == doc["records_hash"]:
        return "DUPLICATE_CONTENT"
    old_auth = active.get("generation_authority", "")
    new_auth = doc["generation_authority"] or ""
    old_gen = active.get("source_generation", "")
    new_gen = doc["source_generation"]
    if old_auth and new_auth and old_auth == new_auth and old_gen and new_gen is not None:
        if int(new_gen) <= int(old_gen):
            raise SnapshotError("SOURCE_GENERATION_NOT_NEWER")
        return None
    return "NEEDS_VERIFICATION"


def _activate(root: Path, doc: dict[str, Any], previous: str | None) -> None:
    generations = root / "generations"
    _private_dir(generations)
    staging = root / "staging"
    _private_dir(staging)
    final = generations / doc["envelope_hash"]
    if final.exists() or final.is_symlink():
        _verify_existing_generation(final, doc)
    else:
        temp = Path(tempfile.mkdtemp(prefix=".build-", dir=staging))
        os.chmod(temp, 0o700)
        try:
            db_path = temp / "index.sqlite"
            _build_sqlite(db_path, doc)
            with db_path.open("rb") as handle:
                os.fsync(handle.fileno())
            _fsync_dir(temp)
            os.replace(temp, final)
            _fsync_dir(staging)
            _fsync_dir(generations)
        except Exception:
            if temp.exists():
                shutil.rmtree(temp, ignore_errors=True)
            raise
    pointer_tmp = root / "CURRENT.tmp"
    _write_synced(pointer_tmp, (doc["envelope_hash"] + "\\n").encode("ascii"))
    os.replace(pointer_tmp, root / "CURRENT")
    _fsync_dir(root)


def index_snapshot(path: Path, root: Path) -> dict[str, Any]:
    raw, doc = read_snapshot_file(path)
    _private_dir(root)
    lock = root / "index.lock"
    fd = os.open(lock, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        os.fchmod(fd, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise SnapshotError("BUSY") from exc
        previous, active = _read_current(root)
        decision = _active_decision(doc, active)
        if decision == "ALREADY_INDEXED" or decision == "DUPLICATE_CONTENT":
            return {"decision": decision, "envelope_hash": doc["envelope_hash"]}
        if decision == "NEEDS_VERIFICATION":
            pending = root / "pending"
            _private_dir(pending)
            target = pending / doc["envelope_hash"]
            if not target.exists():
                temp = pending / (doc["envelope_hash"] + ".tmp")
                _write_synced(temp, raw)
                os.replace(temp, target)
                _fsync_dir(pending)
            return {"decision": "NEEDS_VERIFICATION", "envelope_hash": doc["envelope_hash"]}
        _activate(root, doc, previous)
        return {"decision": "INDEXED", "envelope_hash": doc["envelope_hash"], "previous": previous}
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def pending_preview(root: Path, envelope_hash: str) -> dict[str, Any]:
    """Return non-content metadata for an exact pending snapshot before approval."""
    if not HEX_RE.fullmatch(envelope_hash):
        raise SnapshotError("ENVELOPE_HASH_INVALID")
    pending_dir = root / "pending"
    if pending_dir.is_symlink() or not pending_dir.is_dir():
        raise SnapshotError("PENDING_NOT_FOUND")
    path = pending_dir / envelope_hash
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        fd = os.open(path, flags)
    except FileNotFoundError as exc:
        raise SnapshotError("PENDING_NOT_FOUND") from exc
    except OSError as exc:
        raise SnapshotError("PENDING_OPEN_FAILED") from exc
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size <= 0 or info.st_size > MAX_BYTES:
            raise SnapshotError("PENDING_FILE_INVALID")
        chunks, total = [], 0
        while total <= MAX_BYTES:
            chunk = os.read(fd, min(65536, MAX_BYTES + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        raw = b"".join(chunks)
    finally:
        os.close(fd)
    doc = parse_snapshot_bytes(raw)
    if doc["envelope_hash"] != envelope_hash:
        raise SnapshotError("PENDING_HASH_MISMATCH")
    # Deliberately omit record content and keys from the operator preview.
    return {
        "snapshot_id": doc["snapshot_id"],
        "created_at_epoch_ms": doc["created_at_epoch_ms"],
        "source": doc["source"],
        "source_generation": doc["source_generation"],
        "generation_authority": doc["generation_authority"],
        "record_count": doc["record_count"],
        "records_hash": doc["records_hash"],
        "envelope_hash": doc["envelope_hash"],
        "hash_scope": doc["hash_scope"],
    }


def approve_pending(root: Path, envelope_hash: str, confirmation: str) -> dict[str, Any]:
    if not HEX_RE.fullmatch(envelope_hash) or confirmation != f"ACTIVATE {envelope_hash}":
        raise SnapshotError("APPROVAL_NOT_CONFIRMED")
    pending_dir = root / "pending"
    _private_dir(pending_dir)
    pending = pending_dir / envelope_hash
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(pending, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_BYTES:
            raise SnapshotError("PENDING_FILE_INVALID")
        raw = b""
        while len(raw) <= MAX_BYTES:
            chunk = os.read(fd, min(65536, MAX_BYTES + 1 - len(raw)))
            if not chunk:
                break
            raw += chunk
    finally:
        os.close(fd)
    doc = parse_snapshot_bytes(raw)
    if doc["envelope_hash"] != envelope_hash:
        raise SnapshotError("PENDING_HASH_MISMATCH")
    _private_dir(root)
    fd = os.open(root / "index.lock", os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise SnapshotError("BUSY") from exc
        previous, active = _read_current(root)
        decision = _active_decision(doc, active)
        if decision in {"IDENTITY_CONFLICT", "ALREADY_INDEXED", "DUPLICATE_CONTENT"}:
            raise SnapshotError("APPROVAL_NOT_APPLICABLE")
        _activate(root, doc, previous)
        pending.unlink()
        _fsync_dir(pending.parent)
        return {"decision": "APPROVED_AND_INDEXED", "envelope_hash": envelope_hash}
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def recover_index(root: Path) -> dict[str, Any]:
    _private_dir(root)
    staging = root / "staging"
    _private_dir(staging)
    for item in staging.iterdir():
        if item.is_symlink() or item.is_file():
            item.unlink()
        elif item.is_dir():
            shutil.rmtree(item)
    _fsync_dir(staging)
    pending = root / "pending"
    if pending.exists() or pending.is_symlink():
        _private_dir(pending)
        for item in pending.glob("*.tmp"):
            if item.is_symlink() or item.is_file():
                item.unlink()
        _fsync_dir(pending)
    for name in ("CURRENT.tmp", "CURRENT.rollback.tmp"):
        pointer_tmp = root / name
        if pointer_tmp.exists() or pointer_tmp.is_symlink():
            pointer_tmp.unlink()
            _fsync_dir(root)
    current, _meta = _read_current(root)
    return {"decision": "RECOVERED", "current": current}


def status_index(root: Path) -> dict[str, Any]:
    current, metadata = _read_current(root)
    if current is None:
        return {"decision": "EMPTY", "current": None, "metadata": None}
    return {"decision": "READY", "current": current, "metadata": metadata}


def search_index(root: Path, query: str, limit: int = 20) -> list[dict[str, Any]]:
    if not query.strip():
        return []
    current, _meta = _read_current(root)
    if current is None:
        return []
    db_path = root / "generations" / current / "index.sqlite"
    uri = "file:" + quote(str(db_path.resolve())) + "?mode=ro"
    try:
        with sqlite3.connect(uri, uri=True) as db:
            rows = db.execute(
                """SELECT r.id, r.raw_json, bm25(search)
                   FROM search JOIN records r ON r.id = search.id
                   WHERE search MATCH ?
                   ORDER BY bm25(search) LIMIT ?""",
                (query, max(1, min(limit, 100))),
            ).fetchall()
    except sqlite3.Error as exc:
        raise SnapshotError("SEARCH_QUERY_INVALID") from exc
    return [
        {"record_id": row[0], "raw_record": json.loads(row[1]), "rank": row[2]}
        for row in rows
    ]


def rollback_generation(root: Path, envelope_hash: str) -> dict[str, Any]:
    if not HEX_RE.fullmatch(envelope_hash):
        raise SnapshotError("ENVELOPE_HASH_INVALID")
    _private_dir(root)
    _private_dir(root / "generations")
    generation = root / "generations" / envelope_hash
    db_path = generation / "index.sqlite"
    if generation.is_symlink() or db_path.is_symlink() or not db_path.is_file():
        raise SnapshotError("GENERATION_NOT_FOUND")
    fd = os.open(root / "index.lock", os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        os.fchmod(fd, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise SnapshotError("BUSY") from exc
        uri = "file:" + quote(str(db_path.resolve())) + "?mode=ro"
        try:
            with sqlite3.connect(uri, uri=True) as db:
                if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise SnapshotError("GENERATION_INVALID")
                meta = dict(db.execute("SELECT key,value FROM metadata"))
                if meta.get("envelope_hash") != envelope_hash:
                    raise SnapshotError("GENERATION_HASH_MISMATCH")
        except sqlite3.Error as exc:
            raise SnapshotError("GENERATION_INVALID") from exc
        previous, _active = _read_current(root)
        temp = root / "CURRENT.rollback.tmp"
        _write_synced(temp, (envelope_hash + "\\n").encode("ascii"))
        os.replace(temp, root / "CURRENT")
        _fsync_dir(root)
        return {"decision": "ROLLED_BACK", "envelope_hash": envelope_hash, "previous": previous}
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("snapshot", type=Path)
    index = sub.add_parser("index")
    index.add_argument("snapshot", type=Path)
    index.add_argument("--root", type=Path, required=True)
    approve = sub.add_parser("approve-activation")
    approve.add_argument("--root", type=Path, required=True)
    approve.add_argument("--envelope-hash", required=True)
    recover = sub.add_parser("recover")
    recover.add_argument("--root", type=Path, required=True)
    status = sub.add_parser("status")
    status.add_argument("--root", type=Path, required=True)
    search = sub.add_parser("search")
    search.add_argument("--root", type=Path, required=True)
    search.add_argument("--limit", type=int, default=20)
    search.add_argument("query")
    rollback = sub.add_parser("rollback")
    rollback.add_argument("--root", type=Path, required=True)
    rollback.add_argument("--envelope-hash", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            _raw, doc = read_snapshot_file(args.snapshot)
            result = {"valid": True, "snapshot_id": doc["snapshot_id"], "record_count": doc["record_count"], "records_hash": doc["records_hash"], "envelope_hash": doc["envelope_hash"]}
        elif args.command == "index":
            result = index_snapshot(args.snapshot, args.root)
        elif args.command == "approve-activation":
            preview = pending_preview(args.root, args.envelope_hash)
            print(json.dumps({"pending_snapshot": preview}, ensure_ascii=True, indent=2))
            confirmation = input(f"Type ACTIVATE {args.envelope_hash} to approve this exact snapshot: ")
            result = approve_pending(args.root, args.envelope_hash, confirmation)
        elif args.command == "status":
            result = status_index(args.root)
        elif args.command == "search":
            result = search_index(args.root, args.query, args.limit)
        elif args.command == "rollback":
            result = rollback_generation(args.root, args.envelope_hash)
        else:
            result = recover_index(args.root)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except SnapshotError as exc:
        print(json.dumps({"status": "REJECTED", "code": str(exc)}), file=sys.stderr)
        return 2
    except Exception:
        print(json.dumps({"status": "REJECTED", "code": "INTERNAL_ERROR"}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
