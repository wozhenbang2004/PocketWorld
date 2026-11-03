#!/usr/bin/env python3
"""Test-only cross-language client for PhoneTaskApplicationFlowTest.

The Java host supplies temporary synthetic identities and its originally signed
grant. Only the fixture's original SQLite peer metadata is seeded. The grant is
verified against a real, exact-pinned mTLS peer before any task is sent. All task
actions use Client -> original Companion HTTP -> original PhoneTaskRouter; this
file contains no phone response server, model, runner, approval or success stub.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import ssl
import stat
import sys
import tempfile
import threading
import time

import device_identity
import device_protocol as protocol
import device_transport as transport
from device_companion import DeviceCompanionService
from phone_task_client import Client, ClientError, strict_json
from pocket_companion import CompanionServer, JobManager

FORMAT = "pocketworld.phone-native-fixture.v1"
OPERATION = "phone-api-native-submit"


class FixtureError(RuntimeError):
    """Only fixed diagnostic codes, never remote bodies or credential paths."""


def require(condition, code):
    if not condition:
        raise FixtureError(code)


def private_write(path, raw):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def fixture_file(directory, value, *, maximum=65536):
    path = Path(value)
    require(path.is_absolute() and not path.is_symlink(), "fixture_path_must_be_explicit")
    require(path.resolve().is_relative_to(directory), "fixture_path_outside_test_directory")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1,
                "fixture_file_not_owned_regular")
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum, "fixture_file_too_large")
    return raw


class NativeFixture:
    def __init__(self, manifest_path):
        self.stage = "manifest"
        self.manifest_path = Path(manifest_path).absolute()
        self.directory = self.manifest_path.parent.resolve()
        self.spec = strict_json(fixture_file(self.directory, str(self.manifest_path)))
        required = {"format", "host", "port", "targetDeviceId", "targetInstanceId", "sourceDeviceId", "sourceInstanceId",
                    "sourceCertificatePem", "sourcePrivateKeyPem", "targetCertificatePem", "grantReceipt", "resourceBinding", "reportPath"}
        require(isinstance(self.spec, dict) and required <= self.spec.keys()
                and not self.spec.keys() - required - {"capabilityVersion", "goal", "answer", "scenario"}, "fixture_manifest_fields")
        self.scenario = self.spec.get("scenario", "happy")
        require(self.scenario in {"happy", "owner_denied", "reconnect_cancelled"}, "fixture_scenario")
        require(self.spec["format"] == FORMAT and self.spec["host"] == "127.0.0.1", "fixture_loopback_only")
        protocol.integer(self.spec["port"], "port", 1, 65535)
        protocol.device_id(self.spec["sourceDeviceId"])
        protocol.device_id(self.spec["targetDeviceId"])
        self.report_path = Path(self.spec["reportPath"])
        require(self.report_path.is_absolute() and not self.report_path.exists()
                and self.report_path.parent.resolve() == self.directory, "fixture_report_must_be_new_sibling")
        self.temporary = None
        self.manager = self.server = self.server_thread = self.connection = None
        self.report = {"format": FORMAT, "operationId": OPERATION, "scenario": self.scenario,
                       "selectedPeerDeviceId": self.spec["targetDeviceId"],
                       "modelInferenceClaim": "synthetic_native_host_fixture", "phoneApprovalPerformedByPython": False}

    def start(self):
        self.stage = "load_synthetic_identity"
        self.temporary = tempfile.TemporaryDirectory(prefix="phone-native-client-")
        root = Path(self.temporary.name)
        identity_directory = root / "identity"
        identity_directory.mkdir(mode=0o700)
        source_der = ssl.PEM_cert_to_DER_cert(fixture_file(self.directory, self.spec["sourceCertificatePem"], maximum=16384).decode("ascii"))
        target_der = ssl.PEM_cert_to_DER_cert(fixture_file(self.directory, self.spec["targetCertificatePem"], maximum=16384).decode("ascii"))
        require(device_identity.device_id(source_der) == self.spec["sourceDeviceId"], "source_certificate_identity_changed")
        require(device_identity.device_id(target_der) == self.spec["targetDeviceId"], "target_certificate_identity_changed")
        private_write(identity_directory / "certificate.der", source_der)
        private_write(identity_directory / "instance-id", self.spec["sourceInstanceId"].encode("ascii"))
        private_write(identity_directory / "private-key.pem", fixture_file(self.directory, self.spec["sourcePrivateKeyPem"], maximum=16384))
        source = device_identity.load_existing_identity(identity_directory)
        state = root / "original-companion-state"
        state.mkdir(mode=0o700)
        token = secrets.token_urlsafe(32)
        token_path = root / "synthetic-local-http-token"
        private_write(token_path, token.encode("ascii"))
        self.manager = JobManager({"listen": "127.0.0.1", "port": 0, "stateDir": str(state), "environments": [],
                                   "devices": {"enabled": True}}, token)
        service = DeviceCompanionService(self.manager, local_identity=source)
        self.manager.device_companion = service
        receipt = self.spec["grantReceipt"]
        grant = receipt["body"]["grant"]
        require(receipt["body"]["targetInstanceId"] == self.spec["targetInstanceId"], "fixture_target_instance_changed")
        require(grant["localDeviceId"] == self.spec["targetDeviceId"] and grant["peerDeviceId"] == source.device_id,
                "fixture_grant_audience_changed")
        epoch = protocol.integer(grant["authorizationEpoch"], "authorizationEpoch", 1)
        # Explicit synthetic pre-existing pairing fixture, on the SAME original
        # Companion SQL. No production endpoint accepts this manifest or SQL seed.
        with self.manager.lock:
            self.manager.ledger.execute("INSERT INTO device_peers VALUES(?,?,?,?,?,0,?)",
                (self.spec["targetDeviceId"], target_der, device_identity.certificate_fingerprint(target_der),
                 self.spec["targetInstanceId"], epoch, self.manager.now()))
            self.manager.ledger.commit()
        service.start()
        self.stage = "native_mtls"

        async def connect():
            admitted = asyncio.get_running_loop().create_future()

            async def bound(connection, peer):
                try:
                    require(peer.device_id == self.spec["targetDeviceId"], "actual_tls_target_changed")
                    route = service.receiver.pairing.accept_outgoing_grant(peer, receipt, self.manager.now())
                    route.require_target_peer(peer, self.manager.now())
                    await service._bind(connection, peer)
                    require(not connection.closed and connection in service.connections, "original_native_bind_refused")
                    admitted.set_result(connection)
                except Exception as error:
                    if not admitted.done():
                        admitted.set_exception(error)
                    raise

            connection = await transport.open_lan("127.0.0.1", self.spec["port"],
                context=transport.native_device_context(source, [target_der], server_side=False),
                expected_certificate_der=target_der, lane="control", on_frame=lambda peer, frame: None,
                on_connection=bound)
            self.connection = connection
            service.tasks.append(asyncio.create_task(connection.run()))
            try:
                await asyncio.wait_for(admitted, 10)
                require(connection.tls.peer.device_id == self.spec["targetDeviceId"], "admitted_tls_target_changed")
            except BaseException:
                await connection.close()
                raise

        self._connect_original = connect
        asyncio.run_coroutine_threadsafe(connect(), service.loop).result(timeout=15)
        self.report["actualPeerDeviceId"] = self.connection.tls.peer.device_id
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.server_thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.server_thread.start()
        self.client = Client("http://127.0.0.1:" + str(self.server.server_port), token_path)
        self.report["transport"] = "Client/CompanionHTTP/PhoneTaskRouter/native_mTLS"
        self.report["sourceDeviceId"] = source.device_id

    def action(self, name, parameters):
        self.stage = name
        return self.client.execute(name, parameters)

    def status(self):
        value = self.action("status", {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"]})
        require(value.get("taskId") == self.report["taskId"] and value.get("operationId") == OPERATION,
                "original_task_identity_changed")
        return value

    def poll(self, predicate, phase):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            value = self.status()
            self.stage = phase
            if predicate(value):
                return value
            if value.get("task", {}).get("state") in {"failed", "cancelled", "expired", "interrupted"}:
                raise FixtureError("original_native_task_stopped_before_expected_phase")
            # Repeated reads of the SAME operation only; no mutation is replayed.
            time.sleep(0.05)
        raise FixtureError("original_native_phase_timeout")

    def owner_barrier(self, command):
        """Coordinate test phases only. The Java side must perform the real native action."""
        self.stage = "wait_" + command
        raw = sys.stdin.readline(512)
        require(bool(raw) and strict_json(raw) == {"command": command}, "fixture_owner_phase_changed")

    def verify_wrong_phone_is_rejected(self):
        try:
            self.action("status", {"operationId": OPERATION, "peerDeviceId": self.spec["sourceDeviceId"]})
        except ClientError as refused:
            require(refused.status == 403, "wrong_phone_must_be_scope_refusal")
            self.report["wrongPhoneRejected"] = True
        else:
            raise FixtureError("wrong_phone_must_not_adopt_original_task")

    def finish(self, final):
        self.report["finalStatus"] = final
        self.report["events"] = self.action("events", {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"],
                                                       "cursor": "0", "limit": 100})
        self.report.update(phase="complete", actualWireState=final["task"]["state"], taskRunUnchanged=True,
                           duplicateSubmitReceiptUnchanged=True)
        return self.report

    def deny_before_start(self, run):
        self.owner_barrier("owner-denied")
        self.verify_wrong_phone_is_rejected()
        denied = self.poll(lambda value: value.get("task", {}).get("state") == "cancelled", "wait_owner_denial")
        require(not denied["task"].get("runId"), "owner_denial_cannot_start_a_run")
        repeated = self.action("run", run)
        require(repeated.get("taskId") == self.report["taskId"] and repeated.get("operationId") == OPERATION,
                "repeated_denied_request_changed_original_task")
        self.report.update(repeatedAfterDenial=repeated, runStarted=False)
        return self.finish(denied)

    def reconnect_then_cancel(self, answer_params):
        service = self.manager.device_companion
        old = self.connection
        asyncio.run_coroutine_threadsafe(old.close(), service.loop).result(timeout=5)
        offline = self.status()
        require(offline.get("currentRead") == "unavailable" and offline.get("phoneStateUnconfirmed") is True
                and "task" not in offline, "offline_read_must_keep_original_outcome_unconfirmed")
        self.report["offlineStatus"] = offline
        # Explicit fixture reconnect to the SAME certificate/device; never a task replay.
        asyncio.run_coroutine_threadsafe(self._connect_original(), service.loop).result(timeout=15)
        require(self.connection is not old and self.connection.tls.peer.device_id == self.spec["targetDeviceId"],
                "reconnect_changed_selected_phone")
        recovered = self.status()
        require(recovered.get("task", {}).get("runId") == self.report["runId"]
                and recovered["task"]["executorEpoch"] == self.report["executorEpoch"], "reconnect_changed_original_run")
        self.report["reconnectedStatus"] = recovered
        self.verify_wrong_phone_is_rejected()
        print(json.dumps({"phase": "reconnected", "taskId": self.report["taskId"], "operationId": OPERATION,
                          "runId": self.report["runId"]}), flush=True)
        self.owner_barrier("cancel-original")
        cancelled = self.action("cancel", {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"],
                                           "cancelOperationId": "phone-api-native-cancel"})
        require(cancelled.get("accepted") is True and cancelled.get("cancellation", {}).get("requested") is True,
                "native_cancel_not_requested")
        self.report["cancelReceipt"] = cancelled
        final = self.poll(lambda value: value.get("task", {}).get("state") == "cancelled", "wait_original_cancel")
        try:
            self.action("answer", answer_params)
        except ClientError as refused:
            require(refused.status in {403, 409}, "cancelled_answer_must_be_native_scope_refusal")
            self.report["answerAfterCancelRejected"] = True
        else:
            raise FixtureError("cancelled_original_question_must_not_accept_answer")
        self.finish(final)
        print(json.dumps({"phase": "cancel_verified", "taskId": self.report["taskId"], "operationId": OPERATION}), flush=True)
        self.owner_barrier("owner-revoked")
        revoked = self.status()
        require(revoked.get("currentRead") == "unavailable" and revoked.get("phoneStateUnconfirmed") is True
                and "task" not in revoked, "revoked_read_must_not_claim_current_phone_state")
        self.report["revokedReadStatus"] = revoked
        return self.report

    def run(self):
        self.start()
        grant = self.spec["grantReceipt"]["body"]["grant"]
        require(grant["modelBudget"] >= 2, "fixture_needs_two_original_model_calls")
        run = {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"], "grantId": grant["grantId"],
               "resourceBinding": self.spec["resourceBinding"],
               "goal": self.spec.get("goal", "Ask which exact fixture section to use, then finish with its answer."),
               "toolCalls": min(4, grant["toolBudget"]), "modelCalls": 2, "ttlMs": 180000,
               "capabilityVersion": self.spec.get("capabilityVersion", "1.0")}
        submitted = self.action("run", run)
        require(submitted.get("accepted") is True and submitted.get("taskId"), "native_goal_not_accepted")
        self.report.update(taskId=submitted["taskId"], initialReceipt=submitted)
        duplicate = self.action("run", run)
        require(submitted == duplicate, "duplicate_submit_changed_original_receipt")
        self.report["duplicateReceipt"] = duplicate
        print(json.dumps({"phase": "submitted", "taskId": submitted["taskId"], "operationId": OPERATION}), flush=True)
        if self.scenario == "owner_denied":
            return self.deny_before_start(run)

        def ordinary(value):
            task = value.get("task", {})
            owner = task.get("result", {}).get("ownerInput", {})
            question = owner.get("ordinaryQuestion", {})
            return (owner.get("originalSessionPresent") is True and question.get("approval") is False
                    and question.get("takeover") is False and bool(question.get("requestId")))

        waiting = self.poll(ordinary, "wait_original_question")
        task = waiting["task"]
        native = task["result"]["ownerInput"]
        question = native["ordinaryQuestion"]
        self.report.update(runId=task["runId"], executorEpoch=task["executorEpoch"], allocationId=native["allocationId"],
                           questionSnapshot=task)
        common = {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"], "taskId": task["taskId"],
                  "runId": task["runId"], "executorEpoch": task["executorEpoch"], "allocationId": native["allocationId"]}
        control = self.action("acquire_control", {**common, "controlOperationId": "phone-api-native-control", "ttlMs": 30000})
        self.report["controlReceipt"] = control
        require(control.get("accepted") is True and control.get("controlLeaseId"), "original_control_not_acquired")
        answer_params = {**common, "controlOperationId": "phone-api-native-answer", "controlLeaseId": control["controlLeaseId"],
                         "originalRequestId": question["requestId"], "waitRevision": question["waitRevision"],
                         "text": self.spec.get("answer", "NATIVE-PHONE-EXACT-ANSWER")}
        if self.scenario == "reconnect_cancelled":
            return self.reconnect_then_cancel(answer_params)
        answered = self.action("answer", answer_params)
        self.report["answerReceipt"] = answered
        require(answered.get("accepted") is True and answered.get("inputSaved") is True, "original_answer_not_saved")
        # A second external click returns the original dispatch row and cannot
        # consume the question twice or create a second native continuation.
        duplicate_answer = self.action("answer", answer_params)
        require(answered == duplicate_answer, "duplicate_answer_changed_original_receipt")
        self.report["duplicateAnswerReceipt"] = duplicate_answer

        def finished(value):
            final = value.get("task", {})
            require(final.get("runId") == self.report["runId"] and final.get("executorEpoch") == self.report["executorEpoch"],
                    "original_run_or_epoch_changed")
            return (final.get("state") in {"needs_reconcile", "succeeded"}
                    and "NATIVE-PHONE-RESULT" in final.get("result", {}).get("summary", ""))

        final = self.poll(finished, "wait_original_result")
        self.report["duplicateAnswerReceiptUnchanged"] = True
        return self.finish(final)

    def write_report(self, value):
        private_write(self.report_path, (json.dumps(value, ensure_ascii=True, indent=2) + "\n").encode("ascii"))

    def close(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        if self.server_thread:
            self.server_thread.join(timeout=5)
        if self.connection and self.manager:
            service = self.manager.device_companion
            if service and service.loop and service.loop.is_running():
                asyncio.run_coroutine_threadsafe(self.connection.close(), service.loop).result(timeout=5)
        if self.manager:
            self.manager.close()
        if self.temporary:
            self.temporary.cleanup()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args(argv)
    fixture = None
    try:
        fixture = NativeFixture(args.manifest)
        report = fixture.run()
        fixture.write_report(report)
        complete = {"phase": "complete", "operationId": OPERATION, "taskId": report["taskId"],
                    "state": report["actualWireState"]}
        for key in ("runId", "executorEpoch"):
            if key in report:
                complete[key] = report[key]
        print(json.dumps(complete), flush=True)
        return 0
    except Exception as error:
        failure = {"phase": "failed", "stage": fixture.stage if fixture else "manifest", "errorType": type(error).__name__}
        if isinstance(error, FixtureError):
            failure["code"] = str(error)
        if isinstance(error, ClientError):
            failure["httpStatus"] = error.status
        if fixture:
            try:
                fixture.write_report({**fixture.report, **failure})
            except OSError:
                pass
        print(json.dumps(failure), flush=True)
        return 1
    finally:
        if fixture:
            fixture.close()


if __name__ == "__main__":
    raise SystemExit(main())
