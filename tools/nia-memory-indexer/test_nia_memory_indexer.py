import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from nia_memory_indexer import (
    SnapshotError,
    index_snapshot,
    records_digest,
    search_index,
    status_index,
    validate_snapshot,
)


def record(record_id, key, content, created=1000, status="REPORTED"):
    return {
        "key": key,
        "content": content,
        "createdAt": created,
        "updatedAt": created,
        "category": "GENERAL",
        "hitCount": 1,
        "source": "synthetic_test",
        "id": record_id,
        "receiptStatus": "CONFIRMED",
        "claimStatus": status,
        "factStatus": "UNKNOWN",
        "resolutionStatus": "NEEDS_VERIFICATION",
        "sourceConversationId": "synthetic-conversation",
        "provenance": [
            {
                "source": "synthetic_test",
                "conversationId": "synthetic-conversation",
                "observedAt": created,
            }
        ],
    }


def snapshot(snapshot_id, created, records, generation=None):
    doc = {
        "format": "nia-memory-snapshot",
        "schema_version": "1.0.0",
        "snapshot_id": snapshot_id,
        "created_at_epoch_ms": created,
        "source": {"system": "NIA15-SYNTHETIC", "device_role": "test"},
        "record_count": len(records),
        "hash_algorithm": "SHA-256",
        "records_hash": records_digest(records),
        "records": records,
    }
    if generation is not None:
        doc["source_generation"] = generation
    return doc


class SnapshotValidationTests(unittest.TestCase):
    def setUp(self):
        self.records = [
            record("syn-001", "notebook", "Fictional user prefers green notebooks."),
            record("syn-002", "workspace", "Fictional user said Room A."),
            record("syn-003", "workspace", "Fictional user later said Room B."),
        ]
        self.doc = snapshot("snap-syn-001", 1000, self.records)

    def test_valid_snapshot(self):
        self.assertEqual(validate_snapshot(self.doc)["record_count"], 3)

    def test_duplicate_ids_rejected_but_duplicate_keys_preserved(self):
        duplicate = copy.deepcopy(self.doc)
        duplicate["records"][2]["id"] = "syn-002"
        duplicate["records_hash"] = records_digest(duplicate["records"])
        with self.assertRaisesRegex(SnapshotError, "duplicate record id"):
            validate_snapshot(duplicate)
        self.assertEqual(self.doc["records"][1]["key"], self.doc["records"][2]["key"])

    def test_missing_field_rejected(self):
        broken = copy.deepcopy(self.doc)
        del broken["records"][0]["provenance"]
        broken["records_hash"] = records_digest(broken["records"])
        with self.assertRaisesRegex(SnapshotError, "missing fields"):
            validate_snapshot(broken)

    def test_hash_mismatch_rejected(self):
        broken = copy.deepcopy(self.doc)
        broken["records"][0]["content"] = "tampered"
        with self.assertRaisesRegex(SnapshotError, "records_hash mismatch"):
            validate_snapshot(broken)

    def test_unknown_fields_preserved(self):
        extended = copy.deepcopy(self.doc)
        extended["records"][0]["futureField"] = {"opaque": [1, 2, 3]}
        extended["records_hash"] = records_digest(extended["records"])
        checked = validate_snapshot(extended)
        self.assertEqual(checked["records"][0]["futureField"], {"opaque": [1, 2, 3]})


class IndexLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "index"
        self.records = [
            record("syn-001", "notebook", "Fictional user prefers green notebooks."),
            record("syn-002", "workspace", "Fictional user said Room A."),
            record("syn-003", "workspace", "Fictional user later said Room B."),
        ]
        self.first = snapshot("snap-syn-001", 1000, self.records, generation=1)

    def tearDown(self):
        self.temp.cleanup()

    def test_index_and_retrieve_original_records(self):
        result = index_snapshot(self.first, self.root)
        self.assertEqual(result["decision"], "INDEXED")
        results = search_index(self.root, "Room")
        self.assertEqual({r["record_id"] for r in results}, {"syn-002", "syn-003"})
        returned = {r["record_id"]: r["raw_record"] for r in results}
        self.assertEqual(returned["syn-002"]["claimStatus"], "REPORTED")
        self.assertEqual(returned["syn-003"]["provenance"][0]["source"], "synthetic_test")

    def test_same_snapshot_is_idempotent(self):
        index_snapshot(self.first, self.root)
        result = index_snapshot(self.first, self.root)
        self.assertEqual(result["decision"], "ALREADY_INDEXED")

    def test_invalid_incoming_snapshot_does_not_replace_active_index(self):
        index_snapshot(self.first, self.root)
        before = status_index(self.root)
        broken = copy.deepcopy(self.first)
        broken["records"][0]["content"] = "tampered"
        with self.assertRaises(SnapshotError):
            index_snapshot(broken, self.root)
        self.assertEqual(status_index(self.root), before)

    def test_changed_content_without_generation_requires_approval(self):
        index_snapshot(self.first, self.root)
        changed = snapshot("snap-syn-002", 2000, [record("syn-004", "desk", "Fictional user likes a wooden desk.")])
        result = index_snapshot(changed, self.root)
        self.assertEqual(result["decision"], "NEEDS_VERIFICATION")
        self.assertEqual(status_index(self.root)["metadata"]["snapshot_id"], "snap-syn-001")

    def test_new_generation_switches_and_retains_previous(self):
        index_snapshot(self.first, self.root)
        second = snapshot(
            "snap-syn-002", 2000,
            [record("syn-004", "desk", "Fictional user likes a wooden desk.")],
            generation=2,
        )
        result = index_snapshot(second, self.root)
        self.assertEqual(result["decision"], "INDEXED")
        self.assertTrue((self.root / "generations" / result["previous_generation"] / "memory.sqlite").is_file())
        self.assertEqual(status_index(self.root)["metadata"]["snapshot_id"], "snap-syn-002")

    def test_same_snapshot_id_with_changed_hash_is_rejected(self):
        index_snapshot(self.first, self.root)
        changed = snapshot("snap-syn-001", 1000, [record("syn-009", "new", "Different content.")], generation=2)
        with self.assertRaisesRegex(SnapshotError, "same snapshot_id"):
            index_snapshot(changed, self.root)
        self.assertEqual(status_index(self.root)["metadata"]["snapshot_id"], "snap-syn-001")

    def test_older_generation_is_rejected(self):
        index_snapshot(self.first, self.root)
        older = snapshot(
            "snap-syn-old", 500,
            [record("syn-old", "old", "Old fictional record.")],
            generation=0,
        )
        with self.assertRaisesRegex(SnapshotError, "not a newer source generation"):
            index_snapshot(older, self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
