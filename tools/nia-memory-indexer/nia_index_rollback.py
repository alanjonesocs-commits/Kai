#!/usr/bin/env python3
"""Explicit rollback helper for the NIA15 Debian retrieval index."""
from __future__ import annotations

import argparse
import fcntl
import json
import os
import sqlite3
from pathlib import Path


def rollback(root: Path, generation: str) -> dict[str, str]:
    """Atomically point CURRENT at a verified retained generation."""
    if not generation or "/" in generation or generation in {".", ".."}:
        raise ValueError("invalid generation name")
    root.mkdir(parents=True, exist_ok=True)
    generations = root / "generations"
    target = generations / generation / "memory.sqlite"
    if not target.is_file():
        raise ValueError("requested generation does not exist")
    with (root / ".index.lock").open("a+b") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            with sqlite3.connect(target) as db:
                result = db.execute("PRAGMA integrity_check").fetchone()
                if not result or result[0] != "ok":
                    raise ValueError("target generation failed SQLite integrity_check")
                metadata = dict(db.execute("SELECT key,value FROM index_metadata"))
                if not metadata.get("snapshot_id") or not metadata.get("records_hash"):
                    raise ValueError("target generation metadata is incomplete")
            current_path = root / "CURRENT"
            previous = None
            if current_path.exists():
                previous = json.loads(current_path.read_text(encoding="utf-8")).get("generation")
            temp = root / ".CURRENT.rollback.tmp"
            temp.write_text(json.dumps({"generation": generation}, separators=(",", ":")), encoding="utf-8")
            with temp.open("rb") as handle:
                os.fsync(handle.fileno())
            os.replace(temp, current_path)
            fd = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
            return {"decision": "ROLLED_BACK", "generation": generation, "previous_generation": previous or ""}
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--generation", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(rollback(args.root, args.generation), indent=2))
        return 0
    except Exception as exc:
        print(json.dumps({"decision": "REJECTED", "error": str(exc)}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
