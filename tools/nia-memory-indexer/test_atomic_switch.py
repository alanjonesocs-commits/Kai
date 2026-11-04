import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import nia_memory_indexer
from nia_memory_indexer import index_snapshot, status_index
from test_nia_memory_indexer import record, snapshot


class AtomicPointerFailureTests(unittest.TestCase):
    def test_pointer_switch_failure_keeps_old_generation_active(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "index"
            first = snapshot("snap-atomic-001", 1000, [record("syn-001", "a", "First fictional record.")], generation=1)
            second = snapshot("snap-atomic-002", 2000, [record("syn-002", "b", "Second fictional record.")], generation=2)
            index_snapshot(first, root)
            before = status_index(root)
            original_replace = os.replace

            def fail_current_pointer(src, dst):
                if Path(dst).name == "CURRENT":
                    raise OSError("synthetic pointer replacement failure")
                return original_replace(src, dst)

            with patch.object(nia_memory_indexer.os, "replace", side_effect=fail_current_pointer):
                with self.assertRaisesRegex(OSError, "pointer replacement failure"):
                    index_snapshot(second, root)

            self.assertEqual(status_index(root), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
