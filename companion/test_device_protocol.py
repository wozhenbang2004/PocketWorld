import copy
import base64
import device_identity as identity
import json
import runpy
from pathlib import Path
import unittest

import device_protocol as p
import delegation_policy as d
import device_pairing as pairing


class DeviceProtocolTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = json.loads((Path(__file__).resolve().parents[1] / "protocol/device-v1/fixtures.json").read_text())

    def test_shared_cross_language_fixtures(self):
        for row in self.fixtures["fixtures"]:
            with self.subTest(row["name"]):
                try:
                    if row["kind"] == "message":
                        p.validate_message(row["message"])
                    else:
                        value = p.parse_request(row["raw"].encode() if row["kind"] == "raw" else json.dumps(row["message"], ensure_ascii=False).encode())
                        if row["valid"]:
                            self.assertEqual(row["canonical"], value._encoded.decode())
                            self.assertEqual(row["argumentsDigest"], p.arguments_digest(value.json()))
                            if "receiverTaskId" in row:
                                self.assertEqual(row["receiverTaskId"], p.receiver_task_id(value))
                    self.assertTrue(row["valid"])
                except p.ProtocolError as error:
                    self.assertFalse(row["valid"])
                    self.assertEqual(row["error"], error.code)

    def test_exact_resource_and_bounded_integer_shared_vectors(self):
        for row in self.fixtures["resourceEquality"]:
            self.assertEqual(row["equal"], p.resource_equal(row["left"], row["right"]), row["name"])
        for row in self.fixtures["integerTokens"]:
            for parser in (p.parse_json_object, p.parse_request):
                with self.subTest(row["name"], parser=parser.__name__):
                    if row["valid"]:
                        if parser is p.parse_json_object:
                            self.assertEqual(row["expected"], parser(row["raw"].encode())["n"])
                    else:
                        with self.assertRaises(p.ProtocolError) as error:
                            parser(row["raw"].encode())
                        self.assertEqual(row["error"], error.exception.code)

    def test_execution_resource_and_routing_hint_shared_vectors(self):
        for group, validator in (("executionResources", lambda row: p.validate_resource_binding(row["capabilityVersion"], row["binding"])), ("connectionHints", lambda row: p.validate_connection_hint(row["hint"]))):
            for row in self.fixtures[group]:
                with self.subTest(group=group, name=row["name"]):
                    if row["valid"]:
                        validator(row)
                    else:
                        with self.assertRaises(p.ProtocolError) as error:
                            validator(row)
                        self.assertEqual(row.get("error", "invalid_request"), error.exception.code)

    def test_non_overlapping_source_share_and_stable_identity_vectors(self):
        for row in self.fixtures["sourceShareIds"]:
            value = row["input"]
            self.assertEqual(row["expected"], d.share_id(value["issuerDeviceId"], value["sourceAllocationId"], value["targetDeviceId"], value["resourceBinding"]))
        for row in self.fixtures["sourceShares"]:
            with self.subTest(row["name"]):
                if row["valid"]:
                    d.validate_wire_proof(row["proof"])
                else:
                    with self.assertRaises(p.ProtocolError) as error:
                        d.validate_wire_proof(row["proof"])
                    self.assertEqual(row["error"], error.exception.code)

    def test_exact_p256_and_canonical_der_signature_shared_vectors(self):
        for row in self.fixtures["deviceSignatureAlgorithms"]:
            with self.subTest(row["name"]):
                der, signature = base64.b64decode(row["certificateDer"]), base64.b64decode(row["signature"])
                if row["valid"]:
                    identity.verify_signature(der, row["message"].encode(), signature)
                else:
                    with self.assertRaises(ValueError):
                        identity.verify_signature(der, row["message"].encode(), signature)

    def test_operation_management_uses_new_control_identity_not_execution_budget(self):
        for row in self.fixtures["operationManagementRequests"]:
            with self.subTest(row["name"]):
                if row["valid"]:
                    p.parse_request(p.canonical_bytes(row["message"]))
                else:
                    with self.assertRaises(p.ProtocolError) as error:p.parse_request(p.canonical_bytes(row["message"]))
                    self.assertEqual(row["error"],error.exception.code)

    def test_method_results_are_typed_bound_and_never_invent_target_runs(self):
        for row in self.fixtures["typedMethodResults"]:
            with self.subTest(row["name"]):
                if row["valid"]:p.validate_message(row["message"])
                else:
                    with self.assertRaises(p.ProtocolError) as error:p.validate_message(row["message"])
                    self.assertEqual(row["error"],error.exception.code)

    def test_authoritative_profile_machine_assets_and_front_door_rejections(self):
        directory=Path(__file__).resolve().parents[1]/"protocol/device-v1"
        checked=runpy.run_path(str(directory/"verify_profile.py"))["verify"]()
        self.assertEqual(p.PROFILE,checked["profileId"])
        self.assertEqual(self.fixtures["profileManifest"]["profileId"],p.PROFILE)
        for row in self.fixtures["profileRequests"]:
            with self.subTest(row["name"]):
                if row["valid"]:p.parse_request(p.canonical_bytes(row["message"]))
                else:
                    with self.assertRaises(p.ProtocolError) as error:p.parse_request(p.canonical_bytes(row["message"]))
                    self.assertEqual(row["error"],error.exception.code)

    def test_exact_target_resources_shared_corpus_forbids_cartesian_expansion(self):
        for row in self.fixtures["targetResourceScopes"]:
            with self.subTest(row["name"]):
                if row["valid"]:
                    d.validate_forwarding_scope(row["scope"])
                    for check in row["checks"]:self.assertEqual(check["allowed"],d.contains_target_resource(row["scope"]["targetResources"],check["targetDeviceId"],check["resourceBinding"]))
                else:
                    with self.assertRaises((p.ProtocolError,PermissionError)):d.validate_forwarding_scope(row["scope"])

    def test_artifact_names_share_native_160_scalar_and_control_path_policy(self):
        for row in self.fixtures["artifactNames"]:
            with self.subTest(row["name"]):
                if row["valid"]:p.validate_message(row["message"])
                else:
                    with self.assertRaises(p.ProtocolError) as error:p.validate_message(row["message"])
                    self.assertEqual(row["error"],error.exception.code)

    def test_limits_are_enforced_before_execution(self):
        for raw in (b" " * 65537, b'{"a":' * 18 + b"0" + b"}" * 18):
            with self.assertRaises(p.ProtocolError) as failure:
                p.parse_request(raw)
            self.assertEqual("limit_exceeded", failure.exception.code)

    def test_same_operation_semantics_exclude_only_network_correlation(self):
        original = self.fixtures["fixtures"][0]["message"]
        retry = copy.deepcopy(original)
        retry["requestId"] = "request-retry"
        retry["traceId"] = "trace-retry"
        self.assertEqual(p.arguments_digest(original), p.arguments_digest(retry))
        for key, changed in (("targetDeviceId", "pw1_" + "c" * 64), ("authorizationEpoch", 2), ("deadline", original["deadline"] + 1)):
            retry = copy.deepcopy(original)
            retry[key] = changed
            self.assertNotEqual(p.arguments_digest(original), p.arguments_digest(retry))

    def test_envelope_mutation_does_not_change_validated_bytes(self):
        value = p.parse_request(json.dumps(self.fixtures["fixtures"][0]["message"]).encode())
        value.arguments["effect"] = "read"
        self.assertTrue(value.side_effecting)

    def test_canonical_unicode_key_order_and_control_escaping(self):
        self.assertEqual('{"a":"\\n/","\ue000":1,"😀":2}', p.canonical_bytes({"😀": 2, "\ue000": 1, "a": "\n/"}).decode())

    def test_shared_pairing_finalize_codec_keeps_commit_and_recovery_separate(self):
        for row in self.fixtures["pairingFinalizeRequests"]:
            with self.subTest(row["name"]):
                try:
                    pairing.parse_pairing_finalize(p.canonical_bytes(row["message"]))
                    self.assertTrue(row["valid"])
                except p.ProtocolError as error:
                    self.assertFalse(row["valid"]);self.assertEqual(row["error"], error.code)

    def test_shared_immutable_pairing_commit_proofs_never_become_permissions(self):
        from cryptography.exceptions import InvalidSignature
        for row in self.fixtures["pairingCommits"]:
            with self.subTest(row["name"]):
                try:
                    pairing._verify_committed_completion(base64.b64decode(row["certificateDer"],validate=True),row["completion"],row["transcript"],row["nowMs"])
                    self.assertTrue(row["valid"])
                except (p.ProtocolError, PermissionError, InvalidSignature, ValueError):
                    self.assertFalse(row["valid"])

    def test_shared_receiver_and_allocation_ids(self):
        for row in self.fixtures["receiverTaskIds"]:
            self.assertEqual(row["expected"], p.receiver_task_id(row["input"]), row["name"])
        for row in self.fixtures["allocationIds"]:
            value = row["input"]
            self.assertEqual(row["expected"], p.allocation_id(value["parentTaskId"], value["targetDeviceId"], value["resourceBinding"], value["kind"], value["parentOperationId"]), row["name"])
        identities = self.fixtures["receiverTaskIds"]
        self.assertEqual(identities[0]["expected"], identities[1]["expected"])
        self.assertNotEqual(identities[2]["expected"], identities[3]["expected"])


if __name__ == "__main__":
    unittest.main()
