# NIA15 memory snapshot and retrieval indexers

This directory currently contains **two separate, synthetic-first format lines**. They must use separate index roots. No automatic migration is performed.

## Safety boundary

- Pixel 9 remains the canonical memory authority.
- These tools accept only an explicitly supplied snapshot file.
- They do not connect to Android, discover private memories, transfer data, or write back to canonical memory.
- Use the fictional checked-in fixtures and generated test records until Android export, consent and transfer have been separately implemented and approved.
- Failed validation, indexing, or activation must never replace the active generation.
- Conflicting claims remain separate records. Duplicate record keys are allowed; duplicate record IDs are rejected.
- Hashes provide canonical semantic integrity, not byte-for-byte file integrity or proof of origin.
- Unsigned activation approval protects against accidental activation by normal workflows; it does not protect against a malicious process running as the same OS user.

## Schema 1.1.0 — NIA-CJ1 verifier and generation index

Files:

- `nia_snapshot_v1_1.py`: strict schema 1.1.0 parser, semantic hash verifier, pending approval, FTS5 index, recovery, and verified generation rollback.
- `nia-memory-snapshot-1.1.schema.json`: envelope/record JSON Schema description. The NIA-CJ1 parser imposes additional token-level rules such as rejecting duplicate object keys and all decimal/exponent number tokens.
- `fixtures/synthetic-memory-snapshot-1.1.json`: shared fictional golden fixture used by Python and Kotlin.
- `test_nia_snapshot_v1_1.py`: parser, hash, identity, approval, SQLite, FTS, crash-window, and rollback tests.

NIA-CJ1 uses UTF-8 without BOM, compact canonical output, UTF-16 code-unit key ordering, strict JSON escaping, no Unicode normalisation, no lone surrogates, no duplicate decoded member names, and signed int64 integer tokens only. Unsupported decimal/exponent tokens and `-0` are rejected. Unknown record fields are retained and hashed; the top-level envelope is closed.

The 32 MiB maximum is **provisional**. It must not be treated as an Android-safe limit until Pixel 9 heap and peak-allocation measurements are completed. The 30-second SQLite build figure is a proposed benchmark target, not a guarantee or hard implementation rule.

The current Android source is read through EncryptedSharedPreferences, whose API returns a fully materialised String. The current size check therefore runs **after that String has been allocated**, but before the JSON parse tree and canonical output are built. It is a parser/secondary-allocation guard, not a true pre-allocation cap. A genuine pre-allocation limit would require a streaming or chunked memory storage/read design; do not claim that the current SharedPreferences API meets that stronger requirement.

### 1.1 commands

From this directory:

    python3 nia_snapshot_v1_1.py validate fixtures/synthetic-memory-snapshot-1.1.json
    python3 nia_snapshot_v1_1.py index fixtures/synthetic-memory-snapshot-1.1.json --root ./nia-index-v1.1
    python3 nia_snapshot_v1_1.py status --root ./nia-index-v1.1
    python3 nia_snapshot_v1_1.py search --root ./nia-index-v1.1 "workspace Room"
    python3 nia_snapshot_v1_1.py recover --root ./nia-index-v1.1
    python3 nia_snapshot_v1_1.py rollback --root ./nia-index-v1.1 --envelope-hash PREVIOUS_64_CHARACTER_HASH
    python3 nia_snapshot_v1_1.py approve-activation --root ./nia-index-v1.1 --envelope-hash PENDING_64_CHARACTER_HASH

Changed snapshots without a matching trusted generation authority/counter are saved as pending and return `NEEDS_VERIFICATION`. Approval requires an interactive exact confirmation:

    ACTIVATE <the exact envelope hash>

Pending bytes are re-read and revalidated before activation. Approval does not override validation failures, identity conflicts, or duplicate content. The approval record is local and unsigned.

The v1.1 `CURRENT` file contains a plain envelope hash and its SQLite file is `generations/<envelope_hash>/index.sqlite`. The explicit rollback command validates the retained database before switching this pointer. Previous generations are retained; no automatic pruning is enabled.

## Legacy schema 1.0.0 — compatibility prototype

The following older files remain for the original v1.0.0 prototype:

- `nia_memory_indexer.py`
- `nia-memory-snapshot-1.0.schema.json`
- `nia_index_rollback.py`
- the original v1.0 fixtures and tests.

Its `CURRENT` pointer is a JSON object and its SQLite database is `memory.sqlite` with `index_metadata`. Its rollback helper is **v1.0-only**.

Do not point the v1.0 and v1.1 tools at the same index root. Their pointer and SQLite layouts are intentionally different. There is no implicit conversion from 1.0.0 to 1.1.0; any legacy conversion requires a separately designed and approved tool.

## Tests

    python3 -m compileall -q .
    python3 -m unittest -v

Tests use fictional records and temporary directories only. They do not use device memory or perform transfer.

## Not yet verified / production gates

- Pixel 9 Android host/Keystore/heap behaviour and actual device install.
- Whether the Pixel 9 app-private rootfs filesystem honours directory/file fsync and same-filesystem atomic rename under the installed PRoot Debian environment.
- Target-Debian SQLite DELETE/FULL staging benchmark.
- End-to-end Android export-consent-transfer-readback activation path.
- Android startup recovery UX if encrypted preference keyset initialisation itself throws before the app can display an Unreadable state.
- Source-generation counter and authority policy in the canonical Android memory store.
- Fuzz/property testing of the Kotlin tokenizer beyond the checked-in unit fixtures.
- Cryptographic signing before introducing untrusted snapshot transfer or automated synchronisation.

Treat schema 1.1.0 as an isolated synthetic implementation until these gates pass. No real-memory transfer is authorised by these tools or tests.
