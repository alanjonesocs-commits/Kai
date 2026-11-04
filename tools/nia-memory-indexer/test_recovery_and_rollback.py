import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import nia_memory_indexer
from nia_memory_indexer import SnapshotError, index_snapshot, load_snapshot, status_index
from nia_index_rollback import rollback

from test_nia_memory_indexer import record, snapshot


class RecoveryAndRollbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "index"
        self.first = snapshot(
            "snap-rollback-001", 1000,
            [record("syn-001", "desk", "Fictional user prefers a wooden desk.")],
            generation=1,
        )
        self.second = snapshot(
            "snap-rollback-002", 2000,
            [record("syn-002", "chair", "Fictional user prefers a blue chair.")],
            generation=2,
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_corrupt_or_interrupted_transfer_is_rejected(self):
        path = Path(self.temp.name) / "partial.json"
        path.write_text('{"format":"nia-memory-snapshot","records":[', encoding="utf-8")
        with self.assertRaises(SnapshotError):
            load_snapshot(path)

    def test_failed_index_build_keeps_previous_generation_active(self):
        index_snapshot(self.first, self.root)
        before = status_index(self.root)
        with patch.object(nia_memory_indexer, "_create_index", side_effect=RuntimeError("synthetic build failure")):
            with self.assertRaises(RuntimeError):
                index_snapshot(self.second, self.root)
        self.assertEqual(status_index(self.root), before)

    def test_explicit_rollback_restores_previous_generation(self):
        first_result = index_snapshot(self.first, self.root)
        second_result = index_snapshot(self.second, self.root)
        self.assertEqual(status_index(self.root)["metadata"]["snapshot_id"], "snap-rollback-002")
        result = rollback(self.root, first_result["generation"])
        self.assertEqual(result["decision"], "ROLLED_BACK")
        self.assertEqual(status_index(self.root)["metadata"]["snapshot_id"], "snap-rollback-001")
        self.assertNotEqual(first_result["generation"], second_result["generation"])

    def test_rollback_rejects_missing_generation(self):
        index_snapshot(self.first, self.root)
        before = status_index(self.root)
        with self.assertRaisesRegex(ValueError, "does not exist"):
            rollback(self.root, "missing-generation")
        self.assertEqual(status_index(self.root), before)

    def test_rollback_rejects_path_traversal(self):
        with self.assertRaisesRegex(ValueError, "invalid generation"):
            rollback(self.root, "../outside")


if __name__ == "__main__":
    unittest.main(verbosity=2)
