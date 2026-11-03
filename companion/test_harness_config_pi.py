"""Pi 1.4.0 real-file transaction tests; no installed CLI/account/inference claim."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from harness_config import ConfigError, HarnessConfigs, JsonFields, PI_FILES
from pi_driver import SAFE_FLAGS
from pocket_companion import JobManager


TOKEN = "fixture-only-companion-token-not-a-real-secret-123456789"
ACCESS = "fixture-only-scoped-gateway-token-1234567890123456"
OTHER_SECRET = "fixture-only-existing-other-provider-not-exported"


class PiHarnessConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-pi-config-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.config_root = self.root / "agent"
        self.config_root.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable = self.bin / "pi"
        self.executable.write_text("#!/bin/sh\nprintf '1.4.0\\n'\n")
        self.executable.chmod(0o700)
        self.target = {"bindingId": "controlled-pi", "family": "pi", "configRoot": str(self.config_root),
                       "executable": str(self.executable), "scope": "PROJECT_SESSION"}
        self.config = {"stateDir": str(self.root / "state"), "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace),
                       "writeAllowed": True, "workers": ["command", "pi"], "commands": {"python": sys.executable}, "harnesses": [self.target]}]}
        self.environment = patch.dict(os.environ, {"PATH": str(self.bin) + os.pathsep + os.environ.get("PATH", "")})
        self.environment.start()
        self.manager = JobManager(self.config, TOKEN)
        self.service = self.manager.harness_configs
        self.settings = self.config_root / "settings.json"
        self.models = self.config_root / "models.json"
        self.settings.write_text('{\n  "defaultProvider": "original", "defaultModel": "original-model",\n  "enabledModels": ["original/original-model"], "theme": "owner-theme"\n}\n')
        self.models.write_text('{\n  "providers": {"original": {"baseUrl": "https://original.invalid/v1", "apiKey": "' + OTHER_SECRET + '", "models": [{"id": "original-model"}]}},\n  "ownerValue": [1, 2]\n}\n')
        self.initial = self.images()

    def tearDown(self):
        self.manager.close()
        self.environment.stop()
        self.temp.cleanup()

    def images(self):
        return {name: (self.config_root / name).read_text(encoding="utf-8") if (self.config_root / name).exists() else None for name in PI_FILES}

    def binding(self):
        return {"environmentId": "computer", "environmentBinding": self.manager.environment_binding("computer"), "bindingId": "controlled-pi"}

    def request(self, operation="pi-original", **overrides):
        lease = {"leaseId": "pi-gateway", "endpoint": "http://127.0.0.1:43117", "accessToken": ACCESS, "expiresAt": self.manager.now() + 240000,
                 "environmentBinding": self.binding()["environmentBinding"], "profileDigest": "a" * 64, "taskId": "task-original", "runId": "run-original", "epoch": 3,
                 "audience": "OWNER_PRIVATE", "purpose": "EXTERNAL_RELAY"}
        self.service.register_endpoint(lease)
        probe = self.service.probe(self.binding())
        return {**self.binding(), "operationId": operation, "identityDigest": probe["identityDigest"], "profileDigest": lease["profileDigest"],
                "endpointLeaseRef": lease["leaseId"], "taskId": lease["taskId"], "runId": lease["runId"], "epoch": lease["epoch"], "model": "fixture-model", **overrides}

    def apply(self, plan):
        return self.service.apply(plan["planId"], {**self.binding(), "operationId": plan["operationId"], "planDigest": plan["planDigest"]})

    def applied(self):
        plan = self.service.plan(self.request())
        receipt = self.apply(plan)
        self.assertEqual("COMMITTED", receipt["state"], receipt)
        return plan, receipt

    def restore(self, operation="pi-restore", original="pi-original"):
        probe = self.service.probe(self.binding())
        plan = self.service.restore_plan(original, {**self.binding(), "operationId": operation, "identityDigest": probe["identityDigest"]})
        return plan, self.apply(plan)

    def restart(self):
        self.manager.close()
        self.manager = JobManager(self.config, TOKEN)
        self.service = self.manager.harness_configs

    def job(self, **overrides):
        job = {"id": "job-original", "environmentId": "computer", "worker": "pi", "model": "pocketworld/fixture-model", "status": "queued", **overrides}
        request = {"harnessConfigOperationId": "pi-original", "harnessOwnerBinding": {"taskId": "task-original", "runId": "run-original", "epoch": 3,
                   "profileDigest": "a" * 64, "endpointLeaseRef": "pi-gateway"}}
        return job, request

    def bind(self, job, request):
        return self.service.job_environment(job, request, self.manager.environments["computer"])

    def partial_apply(self, *, after_write=False):
        plan = self.service.plan(self.request())
        replace = self.service._replace_pi_file
        def fail(target, name, before, after, current):
            if name == "settings.json":
                if after_write:
                    replace(target, name, before, after, current)
                raise OSError("controlled unknown second-file effect")
            return replace(target, name, before, after, current)
        with patch.object(self.service, "_replace_pi_file", side_effect=fail):
            receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        return plan, receipt

    def test_probe_is_exact_schema_and_keeps_runtime_and_assets_unverified(self):
        probe = self.service.probe(self.binding())
        self.assertEqual("pi-settings-models-v1", probe["identity"]["configSchemaVersion"])
        self.assertTrue(probe["capabilities"]["configWritable"])
        for field in ("protocolVerified", "modelInferenceVerified", "toolRoundTripVerified", "assetProjection", "assetRuntimeVerified"):
            self.assertFalse(probe["capabilities"][field])
        self.assertFalse(probe["runtimeVerified"])
        self.assertEqual(self.initial, self.images())
        self.assertFalse(self.manager.jobs)
        self.executable.write_text("#!/bin/sh\nprintf '1.4.1\\n'\n")
        probe = self.service.probe(self.binding())
        self.assertEqual("UNKNOWN_SCHEMA", probe["identity"]["configSchemaVersion"])
        self.assertFalse(probe["capabilities"]["configWritable"])
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_two_file_preview_apply_receipt_and_exact_restore(self):
        plan, receipt = self.applied()
        self.assertEqual(list(PI_FILES), [item["pathRef"] for item in plan["filePlans"]])
        self.assertEqual(list(PI_FILES), [item["pathRef"] for item in receipt["files"]])
        selectors = {item["field"]: item for item in plan["filePlans"][0]["operations"]}
        self.assertEqual({"value": "fixture-model"}, selectors["defaultModel"]["after"])
        self.assertEqual({"value": "original-model"}, selectors["defaultModel"]["before"])
        self.assertEqual({"value": "pocketworld"}, selectors["defaultProvider"]["after"])
        self.assertEqual({"value": ["pocketworld/fixture-model"]}, selectors["enabledModels"]["after"])
        for expected, actual in zip(plan["filePlans"], receipt["files"]):
            self.assertEqual(expected["afterDigest"], actual["actualDigest"])
            self.assertTrue(actual["matchesPlan"])
            self.assertEqual("READBACK_VERIFIED", actual["state"])
        public = json.dumps([plan, receipt])
        for secret in (ACCESS, OTHER_SECRET):
            self.assertNotIn(secret, public)
        self.assertNotIn("referenceAssetsDigest", plan)
        self.assertFalse(receipt["runtimeVerified"])
        settings, models = json.loads(self.settings.read_text()), json.loads(self.models.read_text())
        self.assertEqual("pocketworld", settings["defaultProvider"])
        self.assertEqual("fixture-model", settings["defaultModel"])
        self.assertEqual(["pocketworld/fixture-model"], settings["enabledModels"])
        provider = models["providers"]["pocketworld"]
        self.assertEqual("http://127.0.0.1:43117/v1", provider["baseUrl"])
        self.assertEqual("openai-responses", provider["api"])
        self.assertTrue(provider["authHeader"])
        self.assertEqual(ACCESS, provider["apiKey"])
        self.assertEqual([{"id": "fixture-model", "name": "fixture-model", "input": ["text"], "reasoning": False}], provider["models"])
        self.assertEqual(0o600, self.models.stat().st_mode & 0o777)
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.images())
        self.assertEqual(0, self.manager.ledger.execute("SELECT count(*) FROM harness_root_leases").fetchone()[0])

    def test_new_pair_deleted_and_bom_original_preserved(self):
        self.settings.unlink()
        self.models.unlink()
        self.applied()
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(dict.fromkeys(PI_FILES), self.images())
        self.settings.write_text("\ufeff" + self.initial["settings.json"])
        self.models.write_text("\ufeff" + self.initial["models.json"])
        baseline = self.images()
        plan = self.service.plan(self.request("bom-apply"))
        self.assertEqual("COMMITTED", self.apply(plan)["state"])
        self.assertTrue(all(value.startswith("\ufeff") for value in self.images().values()))
        _, restored = self.restore("bom-restore", "bom-apply")
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(baseline, self.images())

    def test_openai_provider_and_common_model_selectors_restore_exactly(self):
        raw = self.settings.read_text()
        for field, value in {"defaultProvider": "openai", "defaultModel": "gpt-5", "enabledModels": ["openai/gpt-5"]}.items():
            raw = JsonFields(raw).set(field, {"value": value})
        self.settings.write_text(raw)
        initial = self.images()
        self.applied()
        plan, receipt = self.restore()
        self.assertEqual([], plan["conflicts"])
        self.assertEqual("COMMITTED", receipt["state"])
        self.assertEqual(initial, self.images())

    def test_managed_pi_api_key_is_revoked_even_when_provider_metadata_changes(self):
        self.applied()
        # Derive a genuine managed key from the actual applied target file. An
        # old protected before-image carrying it must be rejected regardless of
        # unrelated provider metadata; do not forge a new permission or operation.
        provider = json.loads(self.models.read_text())["providers"]["pocketworld"]
        provider["name"] = "Renamed provider"
        provider["baseUrl"] = "https://changed.invalid/v1"
        self.assertTrue(self.service._revoked_before("models.providers.pocketworld", {"value": provider}))
        provider["apiKey"] = OTHER_SECRET
        self.assertFalse(self.service._revoked_before("models.providers.pocketworld", {"value": provider}))

    def test_unrelated_edits_are_preserved_in_both_files_during_restore(self):
        self.applied()
        self.settings.write_text(JsonFields(self.settings.read_text()).set("theme", {"value": "external-theme"}))
        self.models.write_text(JsonFields(self.models.read_text()).set("external", {"value": {"keep": True}}))
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("external-theme", json.loads(self.settings.read_text())["theme"])
        self.assertTrue(json.loads(self.models.read_text())["external"]["keep"])
        self.assertNotIn("pocketworld", json.loads(self.models.read_text())["providers"])
        self.assertEqual(OTHER_SECRET, json.loads(self.models.read_text())["providers"]["original"]["apiKey"])

    def test_external_additions_to_new_files_are_preserved_without_credentials(self):
        self.settings.unlink()
        self.models.unlink()
        self.applied()
        self.settings.write_text(JsonFields(self.settings.read_text()).set("theme", {"value": "keep"}))
        self.models.write_text(JsonFields(self.models.read_text()).set("owner", {"value": True}))
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual({"theme": "keep"}, json.loads(self.settings.read_text()))
        self.assertEqual({"owner": True}, json.loads(self.models.read_text()))

    def test_each_auth_member_drift_blocks_the_entire_cross_file_restore(self):
        self.applied()
        applied = self.images()
        fields = [(self.settings, "defaultProvider", "external"), (self.settings, "defaultModel", "external-model"),
                  (self.settings, "enabledModels", ["external/*"]), (self.models, "providers.pocketworld.baseUrl", "https://external.invalid"),
                  (self.models, "providers.pocketworld.apiKey", "fixture-external-token")]
        for number, (file, field, value) in enumerate(fields):
            with self.subTest(field=field):
                file.write_text(JsonFields(file.read_text()).set(field, {"value": value}))
                outside = self.images()
                plan, receipt = self.restore("auth-drift-" + str(number))
                self.assertEqual("effective_authentication", plan["conflicts"][0]["group"])
                self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
                self.assertEqual(outside, self.images())
                for name in PI_FILES:
                    (self.config_root / name).write_text(applied[name])

    def test_both_json_parsers_reject_duplicate_keys_and_unowned_namespace(self):
        for file in (self.settings, self.models):
            with self.subTest(file=file.name):
                initial = file.read_text()
                file.write_text('{"nested": {"value": 1, "value": 2}}')
                self.assertFalse(self.service.probe(self.binding())["capabilities"]["configWritable"])
                with self.assertRaises(ConfigError):
                    self.service.plan(self.request())
                file.write_text(initial)
        self.models.write_text(JsonFields(self.models.read_text()).set("providers.pocketworld", {"value": {"apiKey": OTHER_SECRET}}))
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_reference_assets_and_pattern_aliases_are_explicitly_rejected(self):
        for extra in ({"referenceAssets": {"sources": []}}, {"model": "other/*"}, {"model": "model:high"}, {"model": "m" * 161}):
            with self.subTest(extra=extra), self.assertRaises(ConfigError):
                self.service.plan(self.request(**extra))
        self.assertEqual(self.initial, self.images())
        with patch.object(self.service, "windows_storage", object()), self.assertRaises(ConfigError):
            self.service.probe(self.binding())

    def test_same_provider_auth_and_project_overlays_are_read_only(self):
        auth = self.config_root / "auth.json"
        auth.write_text('{"original":{"type":"api_key","key":"' + OTHER_SECRET + '"}}')
        self.assertTrue(self.service.probe(self.binding())["capabilities"]["configWritable"])
        auth.write_text('{"pocketworld":{"type":"api_key","key":"' + OTHER_SECRET + '"}}')
        self.assertFalse(self.service.probe(self.binding())["capabilities"]["configWritable"])
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())
        auth.unlink()
        (self.workspace / ".pi").mkdir()
        (self.workspace / ".pi" / "settings.json").write_text('{}')
        self.assertFalse(self.service.probe(self.binding())["capabilities"]["configWritable"])
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())
        self.assertEqual(self.initial, self.images())

    def test_model_file_drift_prevents_any_write_and_operation_does_not_replay(self):
        request = self.request()
        plan = self.service.plan(request)
        self.assertEqual(plan, self.service.plan(request))
        self.models.write_text(self.models.read_text() + " \n")
        outside = self.images()
        receipt = self.apply(plan)
        self.assertEqual("CONFLICT", receipt["state"])
        self.assertEqual(outside, self.images())
        with patch.object(self.service, "_replace_pi_file", side_effect=AssertionError("must not replay")):
            self.assertEqual(receipt, self.apply(plan))
        with self.assertRaises(ConfigError):
            self.service.apply(plan["planId"], {**self.binding(), "operationId": plan["operationId"], "planDigest": "f" * 64})

    def test_partial_apply_receipts_are_per_file_and_new_restore_compensates(self):
        plan, receipt = self.partial_apply()
        self.assertFalse(receipt["files"][0]["matchesPlan"])
        self.assertTrue(receipt["files"][1]["matchesPlan"])
        self.assertEqual(["models.json", "settings.json"], [item["pathRef"] for item in receipt["configurationWriteJournal"]])
        self.assertEqual("WRITE_INTENT", receipt["configurationWriteJournal"][-1]["state"])
        with patch.object(self.service, "_replace_pi_file", side_effect=AssertionError("must not reissue unknown effect")):
            self.assertEqual(receipt, self.apply(plan))
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.images())

    def test_after_write_unknown_effect_uses_durable_intent_without_reissue(self):
        _, receipt = self.partial_apply(after_write=True)
        self.assertTrue(all(item["matchesPlan"] for item in receipt["files"]))
        self.assertEqual("WRITE_INTENT", receipt["configurationWriteJournal"][-1]["state"])
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.images())

    def test_partial_apply_external_file_edit_cannot_be_claimed_as_our_image(self):
        self.partial_apply()
        self.models.write_text(JsonFields(self.models.read_text()).set("external", {"value": True}))
        outside = self.images()
        plan, receipt = self.restore()
        self.assertTrue(plan["conflicts"])
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(outside, self.images())

    def test_cold_partial_compensation_keeps_old_receipt_and_no_endpoint_authority(self):
        _, receipt = self.partial_apply()
        self.restart()
        self.assertFalse(self.service.endpoints)
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(receipt, self.service.operation("pi-original"))
        self.assertEqual(self.initial, self.images())
        self.assertFalse(self.service.endpoints)

    def test_partial_restore_can_be_compensated_under_original_operation_after_restart(self):
        self.applied()
        self.settings.write_text(JsonFields(self.settings.read_text()).set("theme", {"value": "external-retained-theme"}))
        self.models.write_text(JsonFields(self.models.read_text()).set("ownerAdded", {"value": "external-retained-model-setting"}))
        replace = self.service._replace_pi_file
        def fail(target, name, before, after, current):
            if name == "models.json":
                raise OSError("controlled partial compensation")
            return replace(target, name, before, after, current)
        with patch.object(self.service, "_replace_pi_file", side_effect=fail):
            _, partial = self.restore()
        self.assertEqual("RECOVERY_REQUIRED", partial["state"])
        self.assertEqual("external-retained-theme", json.loads(self.settings.read_text())["theme"])
        self.assertIn(ACCESS, self.models.read_text())
        self.restart()
        _, restored = self.restore("pi-second-restore")
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("external-retained-theme", json.loads(self.settings.read_text())["theme"])
        self.assertEqual("external-retained-model-setting", json.loads(self.models.read_text())["ownerAdded"])
        self.assertNotIn("pocketworld", json.loads(self.models.read_text())["providers"])
        self.assertEqual(partial, self.service.operation("pi-restore"))

    def test_reconfiguration_and_partial_reconfiguration_preserve_added_fields(self):
        self.applied()
        self.settings.write_text(JsonFields(self.settings.read_text()).set("theme", {"value": "owner-new-theme"}))
        self.models.write_text(JsonFields(self.models.read_text()).set("ownerAdded", {"value": True}))
        second = self.service.plan(self.request("pi-second", model="second-model"))
        self.assertEqual("COMMITTED", self.apply(second)["state"])
        self.settings.write_text(JsonFields(self.settings.read_text()).set("laterOwnerValue", {"value": True}))
        third = self.service.plan(self.request("pi-third", model="third-model"))
        replace = self.service._replace_pi_file
        def fail(target, name, before, after, current):
            if name == "settings.json":
                raise OSError("controlled partial reconfiguration")
            return replace(target, name, before, after, current)
        with patch.object(self.service, "_replace_pi_file", side_effect=fail):
            self.assertEqual("RECOVERY_REQUIRED", self.apply(third)["state"])
        _, restored = self.restore("restore-third", "pi-third")
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("owner-new-theme", json.loads(self.settings.read_text())["theme"])
        self.assertTrue(json.loads(self.settings.read_text())["laterOwnerValue"])
        self.assertTrue(json.loads(self.models.read_text())["ownerAdded"])
        self.assertNotIn("pocketworld", json.loads(self.models.read_text())["providers"])
        self.assertEqual("original", json.loads(self.settings.read_text())["defaultProvider"])

    def test_lease_revocation_between_files_stops_second_write(self):
        plan = self.service.plan(self.request())
        replace = self.service._replace_pi_file
        def revoke(target, name, before, after, current):
            result = replace(target, name, before, after, current)
            self.service.endpoints.clear()
            return result
        with patch.object(self.service, "_replace_pi_file", side_effect=revoke):
            receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(self.initial["settings.json"], self.settings.read_text())
        self.assertIn(ACCESS, self.models.read_text())
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(self.initial, self.images())

    def test_job_binding_checks_model_task_epoch_and_keeps_safe_command(self):
        self.applied()
        for model in (None, "fixture-model", "original/original-model"):
            with self.subTest(model=model), self.assertRaises(ConfigError):
                self.bind(*self.job(model=model))
        for key, wrong in (("taskId", "other-task"), ("runId", "other-run"), ("epoch", 2)):
            job, request = self.job()
            request["harnessOwnerBinding"][key] = wrong
            with self.subTest(key=key), self.assertRaises(ConfigError):
                self.bind(job, request)
        job, request = self.job()
        self.assertEqual({"PI_CODING_AGENT_DIR": str(self.config_root)}, self.bind(job, request))
        argv = ["pi", *SAFE_FLAGS, "--mode", "rpc"]
        self.assertEqual([str(self.executable), *argv[1:]], self.service.job_command(job, argv))
        self.assertEqual("pi", argv[0])
        self.assertTrue(self.service.job_live(job))
        # The _run request only has the two original references; model comes
        # from the original job, never from a caller's replacement request.
        self.assertEqual({"PI_CODING_AGENT_DIR": str(self.config_root)}, self.bind(job, request))

    def test_path_mismatch_and_late_executable_change_block_configured_job(self):
        self.applied()
        other = self.root / "other-bin"
        other.mkdir()
        (other / "pi").write_text("#!/bin/sh\nexit 0\n")
        (other / "pi").chmod(0o700)
        with patch.dict(os.environ, {"PATH": str(other) + os.pathsep + os.environ["PATH"]}), self.assertRaises(ConfigError):
            self.bind(*self.job())
        job, request = self.job()
        self.bind(job, request)
        self.executable.write_text("#!/bin/sh\nprintf '1.4.0\\n'\n# different binary\n")
        self.assertFalse(self.service.job_live(job))
        with self.assertRaises(ConfigError):
            self.service.job_command(job, ["pi", *SAFE_FLAGS])

    def test_late_auth_or_project_overlay_blocks_bind_and_live(self):
        self.applied()
        auth = self.config_root / "auth.json"
        auth.write_text('{"pocketworld":{"type":"api_key","key":"external"}}')
        with self.assertRaises(ConfigError):
            self.bind(*self.job())
        self.assertTrue(self.service.effective_state(self.binding())["drifted"])
        # Failed attach correctly marks recovery; a distinct fixture operation
        # must not bypass that receipt by simply deleting the outside override.
        self.assertEqual("RECOVERY_REQUIRED", self.service.operation("pi-original")["state"])
        auth.unlink()
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        plan = self.service.plan(self.request("pi-after-restore"))
        self.assertEqual("COMMITTED", self.apply(plan)["state"])
        job, request = self.job()
        request["harnessConfigOperationId"] = "pi-after-restore"
        self.bind(job, request)
        (self.workspace / ".pi").mkdir()
        (self.workspace / ".pi" / "settings.json").write_text('{}')
        self.assertFalse(self.service.job_live(job))

    def test_active_or_unknown_job_does_not_release_root_without_actual_cleanup(self):
        self.applied()
        job, request = self.job()
        self.bind(job, request)
        for status in ("queued", "unknown", "completed"):
            job["status"] = status
            self.service.settle_job(job, True)
            with self.subTest(status=status), self.assertRaises(ConfigError):
                self.restore("blocked-" + status)
        self.restart()
        with self.assertRaises(ConfigError):
            self.restore("cold-unknown")

    def test_model_file_symlink_never_overwrites_target(self):
        outside = self.root / "outside.json"
        outside.write_text('{"untouched": true}')
        self.models.unlink()
        self.models.symlink_to(outside)
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())
        self.assertEqual('{"untouched": true}', outside.read_text())


if __name__ == "__main__":
    unittest.main()
