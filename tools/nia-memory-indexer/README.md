# NIA15 Debian memory retrieval indexer (synthetic-first)

This is a standalone Debian-side prototype. It accepts an explicitly supplied,
versioned JSON snapshot, validates it, builds a disposable SQLite/FTS5 index,
and atomically activates a completed generation.

## Safety boundary

- Pixel 9 remains the canonical memory authority.
- This tool never connects to Android and never writes to canonical memory.
- It does not transfer or discover private memory.
- Use synthetic fixtures until the Android export and consent workflow are
  separately implemented and approved.
- A failed validation or index build must not replace the active generation.
- Conflicting claims are retained as separate records. Duplicate key values
  are allowed; duplicate id values are rejected.
- The tool preserves each complete input record as JSON in SQLite. FTS5 is
  only a search aid; results return the original stored record.

## Current implementation

The Python indexer uses only Python's standard library and SQLite FTS5.
The indexer supports:

- snapshot envelope and SHA-256 validation;
- the currently inspected NIA15 MemoryEntry field names;
- preservation of unknown future record fields;
- duplicate-ID rejection without deduplicating claims sharing a key;
- SQLite integrity checks;
- versioned index generations and atomic CURRENT pointer replacement;
- retention of prior generations for rollback;
- explicit handling of uncertain snapshot ordering;
- FTS5 retrieval of original records.

## Commands

From this directory:

    python3 nia_memory_indexer.py validate synthetic-snapshot.json
    python3 nia_memory_indexer.py index synthetic-snapshot.json --root ./nia-index
    python3 nia_memory_indexer.py status --root ./nia-index
    python3 nia_memory_indexer.py search --root ./nia-index "green notebook"

When a source generation counter is unavailable, a changed snapshot returns
NEEDS_VERIFICATION rather than silently treating timestamps as authoritative.
The --accept-uncertain-order option is an explicit operator override and is
not wired to automatic synchronisation.

## Tests

    python3 -m unittest -v

The tests create fictional records in temporary directories. They do not
access device memory or perform file transfer.

## Not yet implemented / requires source and device verification

- Android snapshot generation from DataRepository.getMemories().
- Explicit user-consent UI and transfer action.
- Actual GuestFileMap destination selection and sandbox path validation.
- Debian package/runtime verification on Pixel 9.
- A supported Android command execution contract returning structured exit
  status. SandboxController.executeCommand() returns text; the streaming
  API's CommandHandle.awaitExit() returns an integer, but a safe production
  bridge still needs design and tests.
- Source-generation counter in the canonical store.
- Pixel 9 end-to-end runtime tests.

Do not treat this prototype as production-ready or connect it to real memories.
