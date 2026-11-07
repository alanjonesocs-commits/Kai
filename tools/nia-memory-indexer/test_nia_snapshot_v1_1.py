import copy
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import nia_snapshot_v1_1 as nia


FIXTURE = Path(__file__).parent / "fixtures" / "synthetic-memory-snapshot-1.1.json"


def record(record_id="synthetic-1", key="workspace", content="Fictional record.", created=1000):
    return {
        "key": key, "content": content, "createdAt": created, "updatedAt": created,
        "category": "GENERAL", "hitCount": 1, "source": "synthetic_test", "id": record_id,
        "receiptStatus": "CONFIRMED", "claimStatus": "REPORTED", "factStatus": "UNKNOWN",
        "resolutionStatus": "NEEDS_VERIFICATION", "sourceConversationId": "synthetic-conversation",
        "provenance": [{"source": "synthetic_test", "conversationId": "synthetic-conversation", "observedAt": created}],
    }


def snapshot(snapshot_id="snapshot-1", records=None, generation=None, authority=None):
    records = records if records is not None else [record()]
    doc = {
        "schema_version": "1.1.0",
        "snapshot_id": snapshot_id,
        "created_at_epoch_ms": 2000,
        "source": {"application": "NIA15", "platform": "Android", "device_label": "synthetic", "system": "NIA15"},
        "source_generation": generation,
        "generation_authority": authority,
        "record_count": len(records),
        "records": records,
        "records_hash": nia.records_digest(records),
        "hash_scope": "snapshot-envelope-v1",
        "envelope_hash": "",
    }
    doc["envelope_hash"] = nia.envelope_digest(doc)
    return doc


class CanonicalAndParsingTests(unittest.TestCase):
    def test_shared_golden_fixture_hashes_and_unknown_fields(self):
        raw, doc = nia.read_snapshot_file(FIXTURE)
        self.assertEqual(len(doc["records"]), 3)
        self.assertEqual(doc["records_hash"], "0fd7b83ee7a898cd608b12447a621dbda4ed3361c6fd8ecab6d929107f7b7fce")
        self.assertEqual(doc["envelope_hash"], "1799c1ba33866cab76c46937dcc5dce7e95077089da6758851ba1fc01b0e73e5")
        self.assertEqual(doc["records"][0]["futureField"]["😀"], "emoji key")

    def test_utf16_code_unit_key_order(self):
        self.assertEqual(nia.canonical_json({"～": 1, "😀": 2}), '{"😀":2,"～":1}'.encode())

    def test_semantic_hash_ignores_whitespace_and_member_order(self):
        doc = json.loads(FIXTURE.read_text(encoding="utf-8"))
        raw = json.dumps(doc, ensure_ascii=False, indent=3).encode()
        checked = nia.parse_snapshot_bytes(raw)
        self.assertEqual(checked["envelope_hash"], doc["envelope_hash"])

    def test_duplicate_decoded_keys_rejected_at_all_depths(self):
        raw = FIXTURE.read_text(encoding="utf-8")
        duplicate_top = raw.replace('"schema_version":"1.1.0"', '"schema_version":"1.1.0","schema_version":"1.1.0"')
        duplicate_escaped = raw.replace('"schema_version":"1.1.0"', '"schema_version":"1.1.0","schema_\\u0076ersion":"1.1.0"')
        duplicate_nested = raw.replace('"futureField":{"😀":"emoji key","～":"fullwidth tilde key"}', '"futureField":{"x":1,"x":2}')
        for payload in (duplicate_top, duplicate_escaped, duplicate_nested):
            with self.subTest(payload=payload[:80]):
                with self.assertRaisesRegex(nia.SnapshotError, "DUPLICATE_OBJECT_KEY"):
                    nia.parse_snapshot_bytes(payload.encode())

    def test_all_numbers_are_integer_tokens_in_int64_range(self):
        for token in ("-0", "1.0", "1e2", "NaN", "Infinity", "9223372036854775808"):
            raw = b'{"unknown":' + token.encode() + b"}"
            with self.subTest(token=token):
                with self.assertRaises(nia.SnapshotError):
                    nia.parse_snapshot_bytes(raw)

    def test_rejects_bom_trailing_data_and_excess_depth(self):
        valid = FIXTURE.read_bytes()
        with self.assertRaisesRegex(nia.SnapshotError, "UTF8_BOM_FORBIDDEN"):
            nia.parse_snapshot_bytes(bytes([0xEF, 0xBB, 0xBF]) + valid)
        with self.assertRaises(nia.SnapshotError):
            nia.parse_snapshot_bytes(valid + b" {}")
        nested = ("[" * 34 + "0" + "]" * 34).encode()
        with self.assertRaises(nia.SnapshotError):
            nia.parse_snapshot_bytes(nested)

    def test_record_unknown_field_depth_limit_counts_envelope_containers(self):
        doc = json.loads(FIXTURE.read_text(encoding="utf-8"))

        def with_nested_arrays(count):
            nested = "bottom"
            for _ in range(count):
                nested = [nested]
            candidate = copy.deepcopy(doc)
            candidate["records"][0]["depthTest"] = nested
            candidate["records_hash"] = nia.records_digest(candidate["records"])
            candidate["envelope_hash"] = nia.envelope_digest(candidate)
            return nia.canonical_json(candidate)

        # Envelope object + records array + record object + 29 unknown arrays = depth 32.
        nia.parse_snapshot_bytes(with_nested_arrays(29))
        with self.assertRaisesRegex(nia.SnapshotError, "JSON_DEPTH_EXCEEDED"):
            nia.parse_snapshot_bytes(with_nested_arrays(30))

    def test_closed_envelope_and_semantic_tamper_detection(self):
        doc = snapshot()
        extra = copy.deepcopy(doc)
        extra["surprise"] = True
        with self.assertRaisesRegex(nia.SnapshotError, "ENVELOPE_FIELDS_INVALID"):
            nia.validate_snapshot(extra)
        changed = copy.deepcopy(doc)
        changed["records"][0]["content"] = "Tampered."
        with self.assertRaisesRegex(nia.SnapshotError, "RECORDS_HASH_MISMATCH"):
            nia.validate_snapshot(changed)

    def test_duplicate_record_keys_allowed_but_duplicate_ids_rejected(self):
        a = record("id-a", "same-key", "Reported Room A")
        b = record("id-b", "same-key", "Reported Room B")
        self.assertEqual(len(nia.validate_snapshot(snapshot(records=[a, b]))["records"]), 2)
        with self.assertRaisesRegex(nia.SnapshotError, "DUPLICATE_RECORD_ID"):
            nia.validate_snapshot(snapshot(records=[a, dict(b, id="id-a")]))


class GenerationActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "index"

    def tearDown(self):
        self.temp.cleanup()

    def put(self, doc, name):
        path = Path(self.temp.name) / name
        path.write_bytes(nia.canonical_json(doc))
        return path

    def stage_for_approval(self, doc, name):
        result = nia.index_snapshot(self.put(doc, name), self.root)
        self.assertEqual(result["decision"], "NEEDS_VERIFICATION")
        return result

    def approve(self, doc):
        return nia.approve_pending(
            self.root,
            doc["envelope_hash"],
            f"ACTIVATE {doc['envelope_hash']}",
        )

    def test_embedded_generation_counter_cannot_authorise_unsigned_auto_activation(self):
        first = snapshot("generation-first", [record("g1", "key", "First.")], 1, "NIA15")
        nia.index_snapshot(self.put(first, "generation-first.json"), self.root)
        second = snapshot("generation-second", [record("g2", "key", "Second.")], 2, "NIA15")
        result = nia.index_snapshot(self.put(second, "generation-second.json"), self.root)
        self.assertEqual(result["decision"], "NEEDS_VERIFICATION")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])
        approved = self.approve(second)
        self.assertEqual(approved["decision"], "APPROVED_AND_INDEXED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), second["envelope_hash"])

    def test_generation_counter_replay_is_rejected_even_when_snapshot_is_unsigned(self):
        first = snapshot("generation-current", [record("g2", "key", "Current.")], 2, "NIA15")
        nia.index_snapshot(self.put(first, "generation-current.json"), self.root)
        older = snapshot("generation-old", [record("g1", "key", "Older.")], 1, "NIA15")
        with self.assertRaisesRegex(nia.SnapshotError, "SOURCE_GENERATION_NOT_NEWER"):
            nia.index_snapshot(self.put(older, "generation-old.json"), self.root)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])

    def test_history_write_failure_does_not_undo_durable_activation(self):
        doc = snapshot("history-failure")
        path = self.put(doc, "history-failure.json")
        with patch.object(nia, "_record_history", return_value=False):
            result = nia.index_snapshot(path, self.root)
        self.assertEqual(result["decision"], "INDEXED")
        self.assertEqual(result["history_status"], "WRITE_FAILED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), doc["envelope_hash"])

    def test_status_reports_empty_then_active_generation(self):
        self.assertEqual(nia.status_index(self.root)["decision"], "EMPTY")
        doc = snapshot()
        nia.index_snapshot(self.put(doc, "status.json"), self.root)
        status = nia.status_index(self.root)
        self.assertEqual(status["decision"], "READY")
        self.assertEqual(status["current"], doc["envelope_hash"])
        self.assertEqual(status["metadata"]["snapshot_id"], doc["snapshot_id"])

    def test_first_activation_and_index_permissions(self):
        path = self.put(snapshot(), "first.json")
        result = nia.index_snapshot(path, self.root)
        self.assertEqual(result["decision"], "INDEXED")
        self.assertEqual(result["history_status"], "RECORDED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), result["envelope_hash"])
        events = [json.loads(p.read_text(encoding="utf-8")) for p in (self.root / "history").glob("*.json")]
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["event_type"], "ACTIVATED")
        self.assertEqual(events[0]["approval_method"], "FIRST_VALID_SNAPSHOT")
        self.assertEqual(events[0]["envelope_hash"], result["envelope_hash"])
        self.assertNotIn("content", events[0])
        self.assertNotIn("records", events[0])
        self.assertEqual(os.stat(self.root).st_mode & 0o777, 0o700)
        self.assertEqual(os.stat(self.root / "index.lock").st_mode & 0o777, 0o600)
        generation = self.root / "generations" / result["envelope_hash"]
        self.assertTrue((generation / "index.sqlite").is_file())

    def test_same_event_changed_hash_is_identity_conflict(self):
        first = self.put(snapshot("same-id"), "first.json")
        nia.index_snapshot(first, self.root)
        changed = snapshot("same-id", [record("other-id", "new", "Changed content")])
        with self.assertRaisesRegex(nia.SnapshotError, "IDENTITY_CONFLICT"):
            nia.index_snapshot(self.put(changed, "changed.json"), self.root)

    def test_same_content_new_event_is_duplicate_not_activation(self):
        doc = snapshot("event-one")
        nia.index_snapshot(self.put(doc, "first.json"), self.root)
        duplicate = snapshot("event-two", copy.deepcopy(doc["records"]))
        result = nia.index_snapshot(self.put(duplicate, "duplicate.json"), self.root)
        self.assertEqual(result["decision"], "DUPLICATE_CONTENT")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), doc["envelope_hash"])

    def test_changed_snapshot_requires_exact_one_time_approval(self):
        first = snapshot("event-one", [record("first", "key", "first")], 1, "NIA15")
        nia.index_snapshot(self.put(first, "first.json"), self.root)
        changed = snapshot("event-two", [record("second", "key", "second")])
        pending_path = self.put(changed, "changed.json")
        result = nia.index_snapshot(pending_path, self.root)
        self.assertEqual(result["decision"], "NEEDS_VERIFICATION")
        preview = nia.pending_preview(self.root, changed["envelope_hash"])
        self.assertEqual(preview["snapshot_id"], changed["snapshot_id"])
        self.assertEqual(preview["records_hash"], changed["records_hash"])
        self.assertEqual(preview["envelope_hash"], changed["envelope_hash"])
        self.assertNotIn("records", preview)
        self.assertNotIn("content", preview)
        with self.assertRaisesRegex(nia.SnapshotError, "APPROVAL_NOT_CONFIRMED"):
            nia.approve_pending(self.root, changed["envelope_hash"], "yes")
        approved = nia.approve_pending(self.root, changed["envelope_hash"], f"ACTIVATE {changed['envelope_hash']}")
        self.assertEqual(approved["decision"], "APPROVED_AND_INDEXED")
        self.assertEqual(approved["history_status"], "RECORDED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), changed["envelope_hash"])
        events = [json.loads(p.read_text(encoding="utf-8")) for p in (self.root / "history").glob("*.json")]
        approved_event = next(event for event in events if event["envelope_hash"] == changed["envelope_hash"])
        self.assertEqual(approved_event["event_type"], "ACTIVATED")
        self.assertEqual(approved_event["approval_method"], "ALAN_INTERACTIVE_APPROVAL")
        self.assertNotIn("records", approved_event)
        self.assertNotIn("content", approved_event)
        with self.assertRaisesRegex(nia.SnapshotError, "PENDING_NOT_FOUND"):
            nia.approve_pending(self.root, changed["envelope_hash"], f"ACTIVATE {changed['envelope_hash']}")

    def test_pending_bytes_are_revalidated_before_approval(self):
        first = snapshot("first", [record("r1", "k", "one")], 1, "authority")
        nia.index_snapshot(self.put(first, "first.json"), self.root)
        changed = snapshot("second", [record("r2", "k", "two")])
        nia.index_snapshot(self.put(changed, "second.json"), self.root)
        pending = self.root / "pending" / changed["envelope_hash"]
        pending.write_text("tampered", encoding="utf-8")
        with self.assertRaises(nia.SnapshotError):
            nia.approve_pending(self.root, changed["envelope_hash"], f"ACTIVATE {changed['envelope_hash']}")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])

    def test_pointer_replace_failure_recovers_old_current(self):
        first = snapshot("first", [record("r1", "k", "one")], 1, "authority")
        nia.index_snapshot(self.put(first, "first.json"), self.root)
        changed = snapshot("second", [record("r2", "k", "two")], 2, "authority")
        self.stage_for_approval(changed, "second.json")
        real_replace = os.replace

        def fail_current(src, dst):
            if Path(dst).name == "CURRENT":
                raise OSError("simulated crash before pointer replacement")
            return real_replace(src, dst)

        with patch.object(nia.os, "replace", side_effect=fail_current):
            with self.assertRaises(OSError):
                self.approve(changed)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])
        self.assertEqual(nia.recover_index(self.root)["current"], first["envelope_hash"])
        self.assertFalse((self.root / "CURRENT.tmp").exists())

    def test_complete_generation_is_reused_after_crash_before_pointer_creation(self):
        first = snapshot("reuse-first", [record("r1", "k", "First.")], 1, "NIA15")
        second = snapshot("reuse-second", [record("r2", "k", "Second.")], 2, "NIA15")
        nia.index_snapshot(self.put(first, "reuse-first.json"), self.root)
        self.stage_for_approval(second, "reuse-second.json")
        final_dir = self.root / "generations" / second["envelope_hash"]
        real_write_synced = nia._write_synced

        def fail_current_pointer(path, data):
            if Path(path).name == "CURRENT.tmp":
                raise OSError("simulated crash after generation rename")
            return real_write_synced(path, data)

        with patch.object(nia, "_write_synced", side_effect=fail_current_pointer):
            with self.assertRaisesRegex(OSError, "generation rename"):
                self.approve(second)
        self.assertTrue((final_dir / "index.sqlite").is_file())
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])

        retried = self.approve(second)
        self.assertEqual(retried["decision"], "APPROVED_AND_INDEXED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), second["envelope_hash"])

    def test_post_replace_pre_directory_fsync_keeps_complete_generation(self):
        first = snapshot("first", [record("r1", "k", "one")], 1, "authority")
        nia.index_snapshot(self.put(first, "first.json"), self.root)
        changed = snapshot("second", [record("r2", "k", "two")], 2, "authority")
        self.stage_for_approval(changed, "second.json")
        real_fsync_dir = nia._fsync_dir

        def fail_root_sync(target):
            if Path(target) == self.root:
                raise OSError("simulated crash after pointer replacement")
            return real_fsync_dir(target)

        with patch.object(nia, "_fsync_dir", side_effect=fail_root_sync):
            with self.assertRaises(OSError):
                self.approve(changed)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), changed["envelope_hash"])
        self.assertEqual(nia.recover_index(self.root)["current"], changed["envelope_hash"])

    def test_hit_count_matches_kotlin_int_range(self):
        valid = record()
        valid["hitCount"] = 2**31 - 1
        nia.validate_snapshot(snapshot(records=[valid]))
        too_large = dict(valid, hitCount=2**31)
        with self.assertRaisesRegex(nia.SnapshotError, "RECORD_HIT_COUNT_INVALID"):
            nia.validate_snapshot(snapshot(records=[too_large]))

    def test_wrong_category_or_status_type_is_rejected_cleanly(self):
        invalid_category = dict(record(), category={"not": "a string"})
        invalid_status = dict(record(), claimStatus=["REPORTED"])
        for invalid in (invalid_category, invalid_status):
            with self.subTest(invalid=invalid):
                with self.assertRaises(nia.SnapshotError):
                    nia.validate_snapshot(snapshot(records=[invalid]))

    def test_search_returns_distinct_conflicting_records(self):
        records = [
            record("room-a", "workspace.preference", "Fictional preference is Room A."),
            record("room-b", "workspace.preference", "Fictional preference later reported as Room B."),
        ]
        doc = snapshot("search-conflicts", records, 1, "NIA15")
        result = nia.index_snapshot(self.put(doc, "search.json"), self.root)
        self.assertEqual(result["decision"], "INDEXED")
        hits = nia.search_index(self.root, "Room")
        self.assertEqual({hit["record_id"] for hit in hits}, {"room-a", "room-b"})
        self.assertEqual({hit["raw_record"]["key"] for hit in hits}, {"workspace.preference"})

    def test_explicit_rollback_switches_to_verified_retained_generation(self):
        first = snapshot("rollback-first", [record("first", "desk", "A fictional wooden desk.")], 1, "NIA15")
        second = snapshot("rollback-second", [record("second", "chair", "A fictional blue chair.")], 2, "NIA15")
        first_result = nia.index_snapshot(self.put(first, "rollback-first.json"), self.root)
        self.stage_for_approval(second, "rollback-second.json")
        second_result = self.approve(second)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), second["envelope_hash"])
        rollback = nia.rollback_generation(self.root, first["envelope_hash"])
        self.assertEqual(rollback["decision"], "ROLLED_BACK")
        self.assertEqual(rollback["history_status"], "RECORDED")
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])
        events = [json.loads(p.read_text(encoding="utf-8")) for p in (self.root / "history").glob("*.json")]
        rollback_event = next(event for event in events if event["event_type"] == "ROLLED_BACK")
        self.assertEqual(rollback_event["envelope_hash"], first["envelope_hash"])
        self.assertEqual(rollback_event["previous_envelope_hash"], second["envelope_hash"])
        self.assertEqual(rollback_event["approval_method"], "EXPLICIT_ROLLBACK")
        self.assertTrue((self.root / "generations" / second["envelope_hash"] / "index.sqlite").is_file())

    def test_crash_after_empty_current_tmp_creation_recovers_old_pointer(self):
        first = snapshot("crash-empty-first", [record("first", "key", "First.")], 1, "NIA15")
        second = snapshot("crash-empty-second", [record("second", "key", "Second.")], 2, "NIA15")
        nia.index_snapshot(self.put(first, "empty-first.json"), self.root)
        self.stage_for_approval(second, "empty-second.json")
        real_write = os.write

        def fail_before_pointer_bytes(fd, data):
            target = os.readlink(f"/proc/self/fd/{fd}")
            if target.endswith("/CURRENT.tmp"):
                raise OSError("simulated crash after empty CURRENT.tmp creation")
            return real_write(fd, data)

        with patch.object(nia.os, "write", side_effect=fail_before_pointer_bytes):
            with self.assertRaises(OSError):
                self.approve(second)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])
        self.assertEqual((self.root / "CURRENT.tmp").stat().st_size, 0)
        self.assertEqual(nia.recover_index(self.root)["current"], first["envelope_hash"])
        self.assertFalse((self.root / "CURRENT.tmp").exists())

    def test_crash_after_full_current_tmp_write_before_fsync_recovers_old_pointer(self):
        first = snapshot("crash-full-first", [record("first", "key", "First.")], 1, "NIA15")
        second = snapshot("crash-full-second", [record("second", "key", "Second.")], 2, "NIA15")
        nia.index_snapshot(self.put(first, "full-first.json"), self.root)
        self.stage_for_approval(second, "full-second.json")
        real_fsync = os.fsync

        def fail_before_pointer_fsync(fd):
            target = os.readlink(f"/proc/self/fd/{fd}")
            if target.endswith("/CURRENT.tmp"):
                raise OSError("simulated crash after full pointer write before fsync")
            return real_fsync(fd)

        with patch.object(nia.os, "fsync", side_effect=fail_before_pointer_fsync):
            with self.assertRaises(OSError):
                self.approve(second)
        self.assertEqual((self.root / "CURRENT").read_text().strip(), first["envelope_hash"])
        self.assertEqual((self.root / "CURRENT.tmp").read_text().strip(), second["envelope_hash"])
        self.assertEqual(nia.recover_index(self.root)["current"], first["envelope_hash"])
        self.assertFalse((self.root / "CURRENT.tmp").exists())

    def test_symlink_snapshot_is_rejected(self):
        target = self.put(snapshot(), "real.json")
        link = Path(self.temp.name) / "linked.json"
        link.symlink_to(target)
        with self.assertRaises(nia.SnapshotError):
            nia.index_snapshot(link, self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
