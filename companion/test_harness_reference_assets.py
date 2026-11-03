"""Exact Book text -> original Harness operation -> real files/readback/update/restore.
No installed production CLI, account, model provider, permission or script execution is claimed.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import test_harness_config as fixtures
import harness_reference_assets as references
from harness_config import ConfigError
from workspace_files import FileError


def package(identity="book-one", revision=1, files=None, kinds=None):
    files = files or {"SKILL.md": "---\nname: original-guide\ndescription: Original literal guide\nallowed-tools: Bash\n---\nLiteral source </tag>\n!`echo NEVER_EXECUTE`\n", "scripts/check.py": "raise AssertionError('reference only, must never execute')\n"}
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.encode() + b"\0" + files[path].encode() + b"\0")
    return {"id": identity, "revision": revision, "digest": digest.hexdigest(), "name": "original-guide", "description": "Original literal guide", "sourceVersion": "v" + str(revision), "files": [{"path": path, "chunks": [text[i:i + 4000] for i in range(0, len(text), 4000)], "sha256": references.sha(text)} for path, text in sorted(files.items())], "kinds": kinds or ["instruction", "skill"], "untrustedReference": True, "scriptsExecutable": False, "permissionsChanged": False}


def manifest(*assets):
    return {"format": references.FORMAT, "profileId": "profile-one", "profileRevision": 1, "resolutionDigest": "d" * 64, "assets": list(assets or [package()]), "scriptsExecutable": False, "permissionsChanged": False}


class HarnessReferenceAssetsTests(unittest.TestCase):
    # Reuse the existing real original Companion fixture without inheriting/rerunning
    # its old test methods or creating a second store/operation authority.
    setUp = fixtures.HarnessConfigurationTests.setUp
    tearDown = fixtures.HarnessConfigurationTests.tearDown
    binding = fixtures.HarnessConfigurationTests.binding
    lease = fixtures.HarnessConfigurationTests.lease
    request = fixtures.HarnessConfigurationTests.request
    apply = fixtures.HarnessConfigurationTests.apply
    restore = fixtures.HarnessConfigurationTests.restore
    claude = fixtures.HarnessConfigurationTests.claude

    def projected(self, value=None, operation="config-original"):
        value = value or manifest()
        plan = self.service.plan(self.request(operation=operation, referenceAssets=value))
        self.assertFalse((self.config_root / "skills").exists(), "preview may not create even directories")
        receipt = self.apply(plan)
        self.assertEqual("COMMITTED", receipt["state"], receipt)
        self.assertEqual(references.manifest_digest(value), receipt["referenceProjection"]["manifestDigest"])
        return plan, receipt

    def test_codex_complete_literal_content_preview_apply_readback_and_original_restore(self):
        (self.config_root / "AGENTS.md").write_text("Owner instruction must retain its original precedence.\n")
        value = manifest(package(files={"SKILL.md": "---\nname: long-guide\ndescription: Literal guide\n---\n" + "正文 </tag> 😀\n" * 1200, "notes.txt": "all resource bytes\n"}))
        plan, receipt = self.projected(value)
        self.assertGreater(len(plan["filePlans"]), 3)
        self.assertNotIn(fixtures.ACCESS, json.dumps(plan))
        self.assertTrue(receipt["referenceProjection"]["effectiveReadback"])
        self.assertFalse(receipt["referenceProjection"]["runtimeVerified"])
        self.assertFalse(receipt["referenceProjection"]["scriptsExecutable"])
        wrapper = next(self.config_root.glob("skills/*/SKILL.md"))
        self.assertNotIn("正文", wrapper.read_text())
        self.assertIn("no tool permissions", wrapper.read_text())
        texts = [path.read_text() for path in self.config_root.glob("skills/*/references/*.txt")]
        self.assertIn("".join(value["assets"][0]["files"][0]["chunks"]), texts)
        for path in self.config_root.glob("skills/**/*"):
            if path.is_file():
                self.assertEqual(0, path.stat().st_mode & 0o111)
        # Outside index edits are not rolled back with our owned block.
        index = self.config_root / "AGENTS.md"
        index.write_text(index.read_text() + "\nOwner added this after apply.\n")
        restored_plan, restored = self.restore()
        self.assertEqual([], restored_plan["conflicts"])
        self.assertEqual("COMMITTED", restored["state"])
        self.assertTrue(restored["referenceProjection"]["restored"])
        self.assertEqual(self.initial, self.file.read_text())
        self.assertEqual("Owner instruction must retain its original precedence.\n\nOwner added this after apply.\n", index.read_text())
        self.assertFalse(list(self.config_root.glob("skills/**/*.txt")))

    def test_claude_original_frontmatter_and_dynamic_commands_remain_literal_not_target_hooks(self):
        self.claude()
        (self.config_root / "CLAUDE.md").write_text("Original Claude owner instruction.\n")
        plan, receipt = self.projected()
        self.assertTrue(any(item["pathRef"] == "CLAUDE.md" for item in plan["filePlans"]))
        wrapper = next(self.config_root.glob("skills/*/SKILL.md")).read_text()
        self.assertNotIn("allowed-tools", wrapper)
        self.assertNotIn("!`", wrapper)
        self.assertFalse((self.workspace / "NEVER_EXECUTE").exists())
        self.assertIn("!`echo NEVER_EXECUTE`", "".join(path.read_text() for path in self.config_root.glob("skills/*/references/*.txt")))
        self.assertTrue(receipt["referenceProjection"]["effectiveReadback"])
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("Original Claude owner instruction.\n", (self.config_root / "CLAUDE.md").read_text())

    def test_source_update_same_identity_exact_revision_retires_old_files_and_restores_initial_base(self):
        self.projected()
        index = self.config_root / "AGENTS.md"
        index.write_text(index.read_text() + "Owner outside addition.\n")
        next_asset = package(revision=2, files={"SKILL.md": "---\nname: original-guide\ndescription: New exact guide\n---\nrevision-two\n", "new.txt": "new resource\n"})
        updated = self.service.plan(self.request(operation="config-updated", referenceAssets=manifest(next_asset)))
        self.assertEqual([], updated["conflicts"])
        receipt = self.apply(updated)
        self.assertEqual("COMMITTED", receipt["state"])
        actual = "".join(path.read_text() for path in self.config_root.glob("skills/*/references/*.txt"))
        self.assertIn("revision-two", actual)
        self.assertNotIn("NEVER_EXECUTE", actual)
        self.assertIn("Owner outside addition.", index.read_text())
        _, restored = self.restore(original="config-updated", operation="restore-updated")
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.file.read_text())
        self.assertEqual("Owner outside addition.\n", index.read_text())

    def test_external_resource_edits_and_unowned_same_namespace_are_preserved_and_block_apply(self):
        self.projected()
        source = next(self.config_root.glob("skills/*/references/*.txt"))
        source.write_text("owner changed the reference file\n")
        before = self.file.read_text()
        preview = self.service.plan(self.request(operation="config-update-conflict", referenceAssets=manifest(package(revision=2))))
        self.assertTrue(preview["conflicts"])
        receipt = self.apply(preview)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual("owner changed the reference file\n", source.read_text())
        self.assertEqual(before, self.file.read_text())

    def test_restore_conflict_preserves_manual_bytes_and_does_not_revive_old_gateway_or_execute(self):
        self.projected()
        resource = next(self.config_root.glob("skills/*/references/*.txt"))
        resource.write_text("manual local source edit\n")
        self.service.endpoints.clear()
        plan, receipt = self.restore()
        self.assertTrue(plan["conflicts"])
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual("manual local source edit\n", resource.read_text())
        self.assertFalse(receipt["referenceProjection"]["scriptsExecutable"])
        self.assertFalse(receipt["referenceProjection"]["restored"])
        self.assertFalse(receipt["referenceProjection"]["applied"])

    def test_forged_partial_bytes_shadow_instruction_and_symlink_are_refused_before_write(self):
        bad = manifest()
        bad["assets"][0]["files"][0]["chunks"] = ["different content"]
        with self.assertRaises((ConfigError, FileError)):
            self.service.plan(self.request(referenceAssets=bad))
        self.assertEqual(self.initial, self.file.read_text())
        (self.config_root / "AGENTS.override.md").write_text("Higher-priority owner instruction\n")
        with self.assertRaises((ConfigError, FileError)):
            self.service.plan(self.request(operation="config-shadow", referenceAssets=manifest()))
        (self.config_root / "AGENTS.override.md").unlink()
        outside = self.root / "outside"
        outside.mkdir()
        (self.config_root / "skills").symlink_to(outside, target_is_directory=True)
        with self.assertRaises((ConfigError, FileError, OSError)):
            self.service.plan(self.request(operation="config-link", referenceAssets=manifest()))
        self.assertEqual([], list(outside.iterdir()))

    def test_partial_original_transaction_has_query_only_receipt_and_can_restore_known_unchanged_base(self):
        plan = self.service.plan(self.request(referenceAssets=manifest()))
        replace = references.replace
        writes = []
        def fail_second(service, target, item):
            writes.append(item["pathRef"])
            if len(writes) == 2:
                raise FileError("controlled storage failure after one original write", 409)
            return replace(service, target, item)
        with patch.object(references, "replace", side_effect=fail_second):
            receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertFalse(receipt["effectiveReadback"])
        self.assertEqual(self.initial, self.file.read_text())
        self.assertEqual(receipt, self.apply(plan), "original in-flight/unknown operation must not replay")
        self.assertEqual(2, len(writes))
        restored_plan, restored = self.restore()
        self.assertEqual([], restored_plan["conflicts"])
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.file.read_text())

    def test_original_job_gates_recheck_asset_drift_and_never_inherit_an_unselected_previous_package(self):
        self.projected()
        job = {"harnessConfigOperationId": "config-original"}
        self.assertTrue(self.service.job_live(job))
        (self.config_root / "AGENTS.override.md").write_text("external shadow\n")
        self.assertFalse(self.service.job_live(job))
        (self.config_root / "AGENTS.override.md").unlink()
        with self.assertRaises(ConfigError):
            self.service.plan(self.request(operation="config-unselected"))
        self.assertFalse(self.service.effective_state(self.binding())["configApplied"], "returning bytes cannot settle the original RECOVERY_REQUIRED receipt")


if __name__ == "__main__":
    unittest.main()
