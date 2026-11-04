# NIA15 memory retrieval pipeline — implementation notes

## Evidence and scope

This branch contains a Debian-side synthetic prototype only. No Android
snapshot export, private-memory transfer, app setting change, or canonical
memory write-back is implemented.

Source was inspected on branch fix/nia15-memory-import-integrity. The feature
branch was created from commit b4b987740a1bb7dc08bec0045e350f3ef7ac9a89.

## Actual MemoryEntry model observed in source

The inspected MemoryEntry is a Kotlin @Serializable data class with:

- key: String
- content: String
- createdAt: Long
- updatedAt: Long
- category: MemoryCategory
- hitCount: Int
- source: String?
- id: String
- receiptStatus: MemoryAssessment
- claimStatus: MemoryAssessment
- factStatus: MemoryAssessment
- resolutionStatus: MemoryAssessment
- sourceConversationId: String?
- provenance: List<MemoryProvenance>

MemoryProvenance contains nullable source, nullable conversationId,
and observedAt: Long.

There is no timestamp field and no explicit conflict field in this model.
Multiple records may share a key; their individual IDs and four assessment
statuses must remain separate. Do not invent a conflict property in the
snapshot. Conflict grouping, if needed, must be derived without mutating the
records and must remain clearly labelled as derived metadata.

## Snapshot contract

The schema file defines envelope version 1.0.0. Records are complete serialized
MemoryEntry objects. All currently declared fields are required in the
snapshot, including nullable fields and fields with Kotlin defaults. Android
serialization must therefore explicitly encode defaults.

The records_hash is SHA-256 over deterministic UTF-8 JSON generated with
sorted object keys, compact separators, Unicode preserved, and non-finite
numbers forbidden. The same canonicalisation must be implemented by the
Android snapshot writer before production transfer is enabled.

Unknown future record fields are accepted and stored in raw_record_json.
No unknown field is discarded by the Debian indexer.

## Actual sandbox API observations

- SandboxController is a common interface extending FileBrowserSource.
- File transfer methods are inherited from FileBrowserSource, not declared
  directly in the visible SandboxController interface.
- Android readTextFile(path, maxBytes, force) returns TextFileResult.
- Android writeTextFile(path, content) returns Boolean.
- executeCommand(command, sessionId) returns a String. Its implementation
  extracts an exit code internally but does not return it as structured data.
- executeCommandStreaming(...) returns CommandHandle; awaitExit() returns an
  integer exit code.

A production transfer bridge must not infer success from empty command output.
It needs a tested structured result contract or must use the streaming handle's
exit status safely.

## Read-only concern requiring further verification

MemoryStore.getAllMemories() calls SettingsJsonList.get(). The memory store
also has a migrateLegacyClaims transformation and an onCorrupt backup callback.
The exact SettingsJsonList.get() implementation and whether reads can trigger
migration writes or corrupt-JSON backup writes must be inspected before
claiming that calling DataRepository.getMemories() is strictly side-effect-free.
Do not add Android snapshot generation until this is resolved.

## Debian index behaviour

- SQLite is disposable and reconstructable from an approved snapshot.
- FTS5 stores searchable text and original record IDs only.
- The complete original record JSON is stored separately and returned for
  every search hit.
- Duplicate IDs are rejected.
- Duplicate keys are allowed so contradictory claims remain independently
  retrievable.
- A new generation is built separately, integrity-checked, and only then
  activated by atomic replacement of the CURRENT pointer.
- Previous generations are retained; no automatic pruning is implemented.
- If no trustworthy source generation counter exists, a changed snapshot
  returns NEEDS_VERIFICATION unless an operator explicitly opts in to
  timestamp-uncertain replacement.
- Same snapshot ID with a different hash is always rejected.

## Known limitations

- The indexer depends on Linux fcntl locking and is intended for Debian/Linux.
- SQLite FTS5 must be available in the target Python build.
- The snapshot size default is 64 MiB and requires measurement before production.
- This prototype does not yet verify the real GuestFileMap path restrictions.
- The prototype does not invoke shell commands from Android.
- No production approval UI or explicit transfer-consent gate exists yet.
- No source-generation counter exists in the inspected MemoryEntry model.
- The test suite is synthetic-only and does not prove Pixel 9 runtime behaviour.

## Next engineering gates

1. Inspect SettingsJsonList and verify side effects of get().
2. Inspect the actual GuestFileMap implementation and allowed paths.
3. Add an Android snapshot writer with canonical hashing and explicit consent.
4. Add a controlled transfer operation with read-back hash verification.
5. Execute Debian validation/indexing through a structured, tested command
   contract.
6. Run Android unit tests, Debian synthetic tests, then Pixel 9 synthetic
   end-to-end tests.
7. Obtain separate approval before any real-memory transfer.


## Additional source verification: file-browser and shell contracts

The common SandboxController interface extends FileBrowserSource. The transfer
methods are inherited, not declared directly on SandboxController.

FileBrowserSource defines:
- readTextFile(path, maxBytes, force): TextFileResult
- writeTextFile(path, content): Boolean

TextFileResult is a sealed result, not a data object with success/content/
bytesRead/truncated/error fields. Its actual variants are:
- Text(content, editable)
- Binary
- TooLarge(sizeBytes)
- Unreadable

The Android controller resolves the supplied guest path through
LinuxSandboxManager.fileMap().resolve(path). A write returns false when path
resolution fails, the target exists but is not a regular file, or an
IOException occurs. It creates parent directories and writes UTF-8 bytes.
This is not an atomic snapshot-write operation; production transfer must write
to a temporary guest filename, read it back, verify the digest, and then
activate it using a separately verified guest-side atomic operation.

The shell API is:
- executeCommand(command, sessionId): String
- executeCommandStreaming(...): CommandHandle
- CommandHandle.awaitExit(): Int

The non-streaming implementation reads an exit_code internally but returns
only combined text. Do not use its text result as a reliable machine-readable
exit status.

LinuxSandboxManager.fileMap() constructs GuestFileMap using the current
rootfs, home and temporary directories, plus the projects directory for
Debian. The actual GuestFileMap.resolve implementation and its traversal/
symlink restrictions remain NEEDS_VERIFICATION.

## Canonical-read side-effect gate

MemoryStore.getAllMemories() delegates to SettingsJsonList.get(). MemoryStore
supplies both migrateLegacyClaims and an onCorrupt callback. The migration
function can assign replacement IDs to duplicate/blank IDs, derive legacy
provenance, and reclassify records sharing a key. Until SettingsJsonList.get()
is inspected, it is not safe to assert that DataRepository.getMemories() is
side-effect-free. Android snapshot generation remains blocked on this check.
