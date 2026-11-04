import unittest
from pathlib import Path

from nia_memory_indexer import load_snapshot, index_snapshot, search_index


class SyntheticFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = Path(__file__).parent / "fixtures" / "synthetic-memory-snapshot.json"

    def test_fixture_is_valid_and_preserves_conflicting_claims(self):
        doc = load_snapshot(self.fixture)
        self.assertEqual(doc["record_count"], 5)
        workspace = [r for r in doc["records"] if r["key"] == "workspace.preference"]
        self.assertEqual({r["id"] for r in workspace}, {"mem-syn-002", "mem-syn-003"})
        self.assertEqual({r["content"].rsplit(" ", 1)[-1] for r in workspace}, {"A.", "B."})

    def test_fixture_indexes_and_searches(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "index"
            index_snapshot(load_snapshot(self.fixture), root)
            results = search_index(root, "workspace")
            ids = {item["record_id"] for item in results}
            self.assertTrue({"mem-syn-002", "mem-syn-003", "mem-syn-004", "mem-syn-005"}.issubset(ids))


if __name__ == "__main__":
    unittest.main(verbosity=2)
