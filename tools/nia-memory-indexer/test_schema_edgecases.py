import copy
import unittest

from nia_memory_indexer import SnapshotError, validate_snapshot
from test_nia_memory_indexer import record, snapshot


class SchemaEdgeCaseTests(unittest.TestCase):
    def test_negative_source_generation_rejected(self):
        doc = snapshot("snap-syn-generation", 1000, [record("syn-001", "k", "Synthetic")], generation=1)
        doc["source_generation"] = -1
        with self.assertRaisesRegex(SnapshotError, "source_generation"):
            validate_snapshot(doc)

    def test_boolean_source_generation_rejected(self):
        doc = snapshot("snap-syn-generation", 1000, [record("syn-001", "k", "Synthetic")], generation=1)
        doc["source_generation"] = True
        with self.assertRaisesRegex(SnapshotError, "source_generation"):
            validate_snapshot(doc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
