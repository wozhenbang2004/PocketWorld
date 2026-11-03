#!/usr/bin/env python3
"""Test-only cross-language client for PhoneTaskApplicationFlowTest.

The Java host supplies temporary synthetic identities and its originally signed
grant. Only the fixture's original SQLite peer metadata is seeded. The grant is
verified against a real, exact-pinned mTLS peer before any task is sent. All task
actions use Client -> original Companion HTTP -> original PhoneTaskRouter; this
file contains no phone response server, model, runner, approval or success stub.
The happy journey discards verified native replies to exercise read-only recovery.
"""
from __future__ import annotations

import argparse
import asyncio
import http.client
import json
import os
from pathlib import Path
import queue
import secrets
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit

import device_identity
import device_protocol as protocol
import device_transport as transport
from device_companion import DeviceCompanionService
from phone_task_bridge import PhoneTaskError
from phone_task_client import Client, ClientError, strict_json
from phone_task_desk import TaskDesk
from phone_task_mcp import VERSION as MCP_VERSION
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
    PROGRESS_STAGES = frozenset({"ordinary_question_wait", "ordinary_question_first_snapshot", "ordinary_question_observed",
        "original_control_acquired", "reconnect_close_requested", "reconnect_closed", "offline_snapshot_observed",
        "reconnect_open_requested", "reconnect_opened", "reconnect_snapshot_observed"})

    def progress(self, stage, snapshot=None):
        """Bounded phase facts only. Never print task text, IDs, leases or raw responses."""
        require(stage in self.PROGRESS_STAGES, "fixture_progress_stage_invalid")
        event = {"phase": "diagnostic", "stage": stage}
        if snapshot is not None:
            task = snapshot.get("task")
            task = task if isinstance(task, dict) else {}
            result = task.get("result", {})
            owner = result.get("ownerInput", {}) if isinstance(result, dict) else {}
            owner = owner if isinstance(owner, dict) else {}
            question = owner.get("ordinaryQuestion", {})
            state = task.get("state")
            event.update(taskPresent=bool(task), currentReadUnavailable=snapshot.get("currentRead") == "unavailable",
                phoneStateUnconfirmed=snapshot.get("phoneStateUnconfirmed") is True,
                taskState=state if isinstance(state, str) and state in protocol.TASK_STATES | {"preparing", "running", "pausing", "paused", "awaiting_user", "needs_confirmation", "cancelled", "failed", "completed", "expired", "interrupted"} else "unknown",
                originalSessionPresent=owner.get("originalSessionPresent") is True,
                ordinaryQuestionPresent=isinstance(question, dict) and bool(question.get("requestId")))
        print(json.dumps(event, separators=(",", ":")), flush=True)

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
        self.endpoint = "http://127.0.0.1:" + str(self.server.server_port)
        self.token_path = token_path
        self.client = Client(self.endpoint, token_path)
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

    def control_snapshot_matches(self, current, original, control):
        """Keep fresh scope failures separate from a transport-unavailable read."""
        if not isinstance(current, dict) or not isinstance(current.get("result"), dict):
            return False, False
        native = original["result"]["ownerInput"]
        owner = current["result"].get("ownerInput", {})
        owner = owner if isinstance(owner, dict) else {}
        question = owner.get("ordinaryQuestion", {})
        question = question if isinstance(question, dict) else {}
        lease = current["result"].get("controlLease", {})
        lease = lease if isinstance(lease, dict) else {}
        scope_matches = (all(current.get(key) == original[key]
                             for key in ("executorDeviceId", "instanceId", "taskId", "runId", "executorEpoch"))
                         and type(current.get("revision")) is int and current["revision"] > original["revision"]
                         and owner.get("allocationId") == native["allocationId"]
                         and all(question.get(key) == native["ordinaryQuestion"][key]
                                 for key in ("requestId", "waitRevision", "approval", "takeover")))
        lease_matches = (lease.get("controlLeaseId") == control.get("controlLeaseId")
                         and lease.get("expiresAt") == control.get("controlLeaseExpiresAt")
                         and type(lease.get("expiresAt")) is int and lease["expiresAt"] > self.manager.now())
        return scope_matches, lease_matches

    def install_control_recovery(self, control_operation, original):
        """Lose real replies on this fixture's router, never fabricate a native result."""
        router = self.manager.device_companion.phone_tasks
        exchange = router._exchange
        facts = {"nativeAcquireCalls": 0, "originalOperationReads": 0, "taskReadsAfterReceipt": 0,
                 "ackDiscarded": False, "ackUnknownObserved": False, "taskReadDiscarded": False,
                 "taskReadUnavailableObserved": False, "recoveredByOriginalOperation": False}
        state = {"readArmed": False, "control": None, "facts": facts}
        self.report["controlRecovery"] = facts

        async def discard_once(connection, peer, ticket, envelope, timeout=12):
            acquire = envelope.method == "task.acquireControl"
            if acquire:
                facts["nativeAcquireCalls"] += 1
            if envelope.method == "operation.get" and envelope.arguments.get("operationId") == control_operation:
                facts["originalOperationReads"] += 1
            task_read = (state["readArmed"] and envelope.method == "task.get"
                         and envelope.arguments.get("taskId") == original["taskId"])
            if task_read:
                facts["taskReadsAfterReceipt"] += 1
            response = await exchange(connection, peer, ticket, envelope, timeout)
            if response["messageType"] == "DeviceResponse":
                if (acquire and envelope.get("operationId") == control_operation
                        and response["result"].get("accepted") is True and not facts["ackDiscarded"]):
                    facts["ackDiscarded"] = True
                    raise PhoneTaskError("Fixture discarded the original control acknowledgement", 504)
                # Do not turn a wrong fresh identity, question, revision or lease
                # into an unavailable read that a later good snapshot could hide.
                if (task_read and not facts["taskReadDiscarded"]
                        and all(self.control_snapshot_matches(response["result"], original, state["control"]))):
                    facts["taskReadDiscarded"] = True
                    raise PhoneTaskError("Fixture discarded one original task read response", 504)
            return response

        router._exchange = discard_once
        return state

    @staticmethod
    def mcp_initialize(request_id):
        return {"jsonrpc": "2.0", "id": request_id, "method": "initialize",
                "params": {"protocolVersion": MCP_VERSION, "capabilities": {},
                           "clientInfo": {"name": "phone-native-fixture", "version": "1"}}}

    @staticmethod
    def mcp_result(response, request_id):
        require(isinstance(response, dict) and response.get("jsonrpc") == "2.0"
                and response.get("id") == request_id and "error" not in response
                and isinstance(response.get("result"), dict), "mcp_response_not_original_result")
        return response["result"]

    def answer_through_stdio(self, parameters):
        """An independent CLI process owns the MCP lifecycle and performs this one write."""
        self.stage = "mcp_stdio_answer"
        process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("phone_tasks.py")),
                                    "--endpoint", self.endpoint, "--token-file", str(self.token_path),
                                    "--phone", self.spec["targetDeviceId"], "mcp"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        received = queue.Queue()

        def read_frames():
            try:
                while True:
                    raw = process.stdout.readline(65537)
                    received.put(raw)
                    if not raw or len(raw) > 65536 or not raw.endswith(b"\n"):
                        return
            except OSError:
                received.put(b"")

        reader = threading.Thread(target=read_frames, name="native-mcp-stdio-fixture", daemon=True)
        reader.start()

        def send(message):
            process.stdin.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
            process.stdin.flush()

        def receive(request_id):
            try:
                raw = received.get(timeout=30)
            except queue.Empty:
                raise FixtureError("mcp_stdio_response_timeout") from None
            require(bool(raw) and len(raw) <= 65536 and raw.endswith(b"\n"), "mcp_stdio_frame_invalid")
            return self.mcp_result(strict_json(raw), request_id)

        try:
            send(self.mcp_initialize(1))
            initialized = receive(1)
            require(initialized.get("protocolVersion") == MCP_VERSION, "mcp_stdio_version_changed")
            send({"jsonrpc": "2.0", "method": "notifications/initialized"})
            send({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                  "params": {"name": "phone_task_answer", "arguments": parameters}})
            process.stdin.close()
            result = receive(2)
            require(result.get("isError") is False and isinstance(result.get("structuredContent"), dict),
                    "mcp_stdio_answer_unconfirmed")
            exit_code = process.wait(timeout=5)
            require(exit_code == 0, "mcp_stdio_process_failed")
            answer = result["structuredContent"]
            require(answer.get("operationId") == parameters["controlOperationId"]
                    and answer.get("targetOperationId") == OPERATION
                    and all(answer.get(key) == parameters[key]
                            for key in ("peerDeviceId", "taskId", "runId", "executorEpoch", "allocationId")),
                    "mcp_stdio_answer_identity_changed")
            self.report["mcpStdio"] = {"protocolVersion": initialized["protocolVersion"],
                                       "selectedPeerDeviceId": self.spec["targetDeviceId"], "tool": "phone_task_answer",
                                       "processExitCode": exit_code, "result": answer}
            return answer
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=1)
            for stream in (process.stdin, process.stdout):
                if stream and not stream.closed:
                    stream.close()

    def verify_desk_question(self, controlled, parameters):
        # Fetch the five-second signed description immediately before the UI
        # contract. Its timestamps/grants and the native snapshot stay unchanged.
        doctor = self.action("doctor", {"peerDeviceId": self.spec["targetDeviceId"]})
        self.stage = "task_desk_native_contract"
        completed = subprocess.run(["node", str(Path(__file__).with_name("test_phone_task_desk.cjs")), "--native-contract"],
                                   input=json.dumps({"doctor": doctor, "status": controlled, "answer": parameters},
                                                    ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20, check=False)
        require(completed.returncode == 0 and len(completed.stdout) <= 4096, "task_desk_native_contract_failed")
        proof = strict_json(completed.stdout)
        require(proof == {"format": "pocketworld.task-desk-native-contract.v1", "answerEnabled": True,
                          "requestMatchesNativeScope": True, "nativeDispatchPerformed": False,
                          "capturedAnswerRequests": 1}, "task_desk_native_contract_result_invalid")
        self.report["deskNativeContract"] = proof

    def require_same_result(self, value, original):
        require(isinstance(value, dict) and value.get("operationId") == OPERATION
                and value.get("peerDeviceId") == self.spec["targetDeviceId"]
                and value.get("taskId") == self.report["taskId"], "mcp_read_changed_original_operation")
        require(isinstance(value.get("task"), dict) and not value.get("currentRead")
                and not value.get("phoneStateUnconfirmed"), "mcp_read_original_snapshot_unavailable")
        actual, previous = value["task"], original["task"]
        changed = [key for key in ("executorDeviceId", "instanceId", "taskId", "runId", "executorEpoch", "state")
                   if actual.get(key) != previous[key]]
        if actual.get("revision", 0) < previous["revision"]:
            changed.append("revision")
        if actual.get("result", {}).get("summary") != previous["result"]["summary"]:
            changed.append("summary")
        # Fixed field names only: expose no result text, credential, identity or
        # remote body if a real native transition violates the result contract.
        require(not changed, "mcp_read_changed_original_result_" + "_".join(changed))

    def read_through_task_desk(self, original):
        """Use the original Desk session over HTTP, then close it without stopping the phone."""
        self.stage = "mcp_http_status"
        desk = TaskDesk(self.client, self.spec["targetDeviceId"])
        try:
            local_session = urlsplit(desk.start())
            require(local_session.hostname == "127.0.0.1" and bool(local_session.fragment), "mcp_desk_not_local_session")
            origin = local_session._replace(path="", fragment="").geturl()

            def exchange(message, expected_status):
                connection = http.client.HTTPConnection(local_session.hostname, local_session.port, timeout=15)
                try:
                    connection.request("POST", "/mcp", json.dumps(message, separators=(",", ":")).encode("utf-8"),
                                       {"Authorization": "Bearer " + local_session.fragment, "Origin": origin,
                                        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                                        "Sec-Fetch-Site": "same-origin", "MCP-Protocol-Version": MCP_VERSION})
                    response = connection.getresponse()
                    raw = response.read(65537)
                    require(response.status == expected_status and len(raw) <= 65536, "mcp_http_response_invalid")
                    return strict_json(raw) if raw else None
                finally:
                    connection.close()

            initialized = self.mcp_result(exchange(self.mcp_initialize(1), 200), 1)
            require(initialized.get("protocolVersion") == MCP_VERSION, "mcp_http_version_changed")
            require(exchange({"jsonrpc": "2.0", "method": "notifications/initialized"}, 202) is None,
                    "mcp_http_acknowledgement_invalid")
            result = self.mcp_result(exchange({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                               "params": {"name": "phone_task_status", "arguments": {
                                                   "operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"]}}}, 200), 2)
            require(result.get("isError") is False and isinstance(result.get("structuredContent"), dict),
                    "mcp_http_read_unconfirmed")
            observed = result["structuredContent"]
            self.require_same_result(observed, original)
            self.report["mcpHttp"] = {"protocolVersion": initialized["protocolVersion"],
                                      "selectedPeerDeviceId": self.spec["targetDeviceId"], "tool": "phone_task_status",
                                      "result": observed}
        finally:
            desk.close()
        require(desk.closed and not desk.session_token, "mcp_desk_session_not_closed")
        after_close = self.status()
        self.require_same_result(after_close, observed)
        self.report["deskClosedPhoneResultAvailable"] = True
        return after_close

    def poll(self, predicate, phase):
        deadline = time.monotonic() + 90
        first = True
        while time.monotonic() < deadline:
            value = self.status()
            if first and phase == "wait_original_question":
                self.progress("ordinary_question_first_snapshot", value)
            first = False
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
                                                       "cursor": "0", "limit": 32})
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
        self.progress("reconnect_close_requested")
        asyncio.run_coroutine_threadsafe(old.close(), service.loop).result(timeout=5)
        self.progress("reconnect_closed")
        offline = self.status()
        require(offline.get("currentRead") == "unavailable" and offline.get("phoneStateUnconfirmed") is True
                and "task" not in offline, "offline_read_must_keep_original_outcome_unconfirmed")
        self.report["offlineStatus"] = offline
        self.progress("offline_snapshot_observed", offline)
        # Explicit fixture reconnect to the SAME certificate/device; never a task replay.
        self.progress("reconnect_open_requested")
        asyncio.run_coroutine_threadsafe(self._connect_original(), service.loop).result(timeout=15)
        self.progress("reconnect_opened")
        require(self.connection is not old and self.connection.tls.peer.device_id == self.spec["targetDeviceId"],
                "reconnect_changed_selected_phone")
        recovered = self.status()
        self.progress("reconnect_snapshot_observed", recovered)
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
        self.owner_barrier("original-stop-confirmed")
        reconciled = self.action("status", {"operationId": "phone-api-native-cancel", "peerDeviceId": self.spec["targetDeviceId"]})
        before, after = cancelled["cancellation"], reconciled.get("cancellation", {})
        require(reconciled.get("operationId") == cancelled["operationId"]
                and reconciled.get("taskId") == self.report["taskId"]
                and reconciled.get("peerDeviceId") == self.spec["targetDeviceId"]
                and all(after.get(key) == before[key] for key in ("targetOperationId", "targetArgumentsDigest"))
                and after.get("requested") is True and after.get("stopped") is True,
                "same_cancel_operation_did_not_report_original_native_stop")
        self.report["reconciledCancelStatus"] = reconciled
        self.report["cancelAcknowledgementStopped"] = before["stopped"]
        print(json.dumps({"phase": "cancel_reconciled", "taskId": self.report["taskId"], "operationId": OPERATION}), flush=True)
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

        # The Java outer phase still has its original 45s window in the reconnect
        # case. These facts locate any time spent in this original 90s poll; they
        # do not extend either deadline or turn an unavailable read into success.
        self.progress("ordinary_question_wait")
        waiting = self.poll(ordinary, "wait_original_question")
        self.progress("ordinary_question_observed", waiting)
        task = waiting["task"]
        native = task["result"]["ownerInput"]
        self.report.update(runId=task["runId"], executorEpoch=task["executorEpoch"], allocationId=native["allocationId"],
                           questionSnapshot=task)
        common = {"operationId": OPERATION, "peerDeviceId": self.spec["targetDeviceId"], "taskId": task["taskId"],
                  "runId": task["runId"], "executorEpoch": task["executorEpoch"], "allocationId": native["allocationId"]}
        control_operation = "phone-api-native-control"
        recovery = self.install_control_recovery(control_operation, task) if self.scenario == "happy" else None
        control = self.action("acquire_control", {**common, "controlOperationId": control_operation, "ttlMs": 30000})
        self.report["controlReceipt"] = control
        unknown = control.get("status") == "unknown" and control.get("effectState") == "unknown"
        if recovery:
            recovery["facts"]["ackUnknownObserved"] = unknown and control.get("accepted") is not True
        if unknown:
            # Query the one original control operation. A task snapshot alone
            # cannot prove that this mutation acquired a lease; never replay it.
            control = self.action("status", {"operationId": control_operation, "peerDeviceId": common["peerDeviceId"]})
            self.report["controlReceipt"] = control
        require(control.get("accepted") is True and control.get("controlLeaseId")
                and control.get("operationId") == control_operation and control.get("targetOperationId") == OPERATION
                and control.get("kind") == "task.acquireControl" and control.get("peerDeviceId") == common["peerDeviceId"]
                and all(control.get(key) == common[key] for key in ("taskId", "runId", "executorEpoch")),
                "original_control_not_acquired")
        if unknown and control.get("currentRead") != "unavailable":
            scope_matches, lease_matches = self.control_snapshot_matches(control.get("task"), task, control)
            require(scope_matches, "acquire_status_changed_original_question_scope")
            require(lease_matches, "original_control_missing_from_real_snapshot")
        elif control.get("currentRead") == "unavailable":
            require("task" not in control, "acquire_status_changed_original_question_scope")
        if recovery:
            recovery["facts"]["recoveredByOriginalOperation"] = unknown
            # Only the next original-task read is armed, after the control
            # receipt query (including its internal task.get) has finished.
            recovery.update(control=control, readArmed=True)
        while True:
            require(type(control.get("controlLeaseExpiresAt")) is int
                    and control["controlLeaseExpiresAt"] > self.manager.now(), "original_control_missing_from_real_snapshot")
            controlled = self.status()
            if controlled.get("currentRead") != "unavailable":
                break
            require("task" not in controlled, "acquire_status_changed_original_question_scope")
            if recovery:
                recovery["facts"]["taskReadUnavailableObserved"] = True
            remaining = control["controlLeaseExpiresAt"] - self.manager.now()
            require(remaining > 0, "original_control_missing_from_real_snapshot")
            time.sleep(min(0.05, remaining / 1000))
        current = controlled.get("task", {})
        scope_matches, lease_matches = self.control_snapshot_matches(current, task, control)
        require(scope_matches, "acquire_status_changed_original_question_scope")
        require(lease_matches, "original_control_missing_from_real_snapshot")
        owner = current["result"]["ownerInput"]
        current_question = owner["ordinaryQuestion"]
        lease = current["result"]["controlLease"]
        if recovery:
            recovery["readArmed"] = False
            facts = recovery["facts"]
            require(facts["nativeAcquireCalls"] == 1 and facts["originalOperationReads"] >= 1
                    and facts["taskReadsAfterReceipt"] >= 2
                    and all(facts[key] is True for key in ("ackDiscarded", "ackUnknownObserved", "taskReadDiscarded",
                            "taskReadUnavailableObserved", "recoveredByOriginalOperation")), "original_control_not_acquired")
        self.progress("original_control_acquired", controlled)
        self.report["controlSnapshot"] = controlled
        # The same published snapshot is what Task Desk reads after acquisition;
        # do not conceal a missing projection by taking the lease from its receipt.
        answer_params = {**common, "controlOperationId": "phone-api-native-answer", "controlLeaseId": lease["controlLeaseId"],
                         "originalRequestId": current_question["requestId"], "waitRevision": current_question["waitRevision"],
                         "text": self.spec.get("answer", "NATIVE-PHONE-EXACT-ANSWER")}
        if self.scenario == "reconnect_cancelled":
            return self.reconnect_then_cancel(answer_params)
        self.verify_desk_question(controlled, answer_params)
        answered = self.answer_through_stdio(answer_params)
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
        print(json.dumps({"phase": "result_observed", "taskId": self.report["taskId"], "operationId": OPERATION,
                          "runId": self.report["runId"], "executorEpoch": self.report["executorEpoch"]}), flush=True)
        # Java waits for the actual original Finished proof and paused control,
        # and keeps the real service/watchdog alive across its next lease tick.
        # This phase command authorizes no task write, model call or new run.
        self.owner_barrier("original-result-stable")
        settled = self.status()
        self.require_same_result(settled, final)
        self.report["nativeResultBarrierAcknowledged"] = True
        final = settled
        final = self.read_through_task_desk(final)
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
