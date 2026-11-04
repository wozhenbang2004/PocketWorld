"""Native owner pairing over the original Companion state and unique device contract.

No business permission is made from candidate JSON or a local HTTP bearer. The
owner reviews two fingerprints plus exact native scopes; a separate mTLS lane
then commits the pairing in the ORIGINAL manager ledger. No key is provisioned
here: initialization is a separate, explicit owner action.
"""
from __future__ import annotations

import copy
import asyncio
import hashlib
import ipaddress
import json
import ntpath
import os
from pathlib import Path
import threading
import uuid

import device_identity
import device_protocol as protocol
from device_companion import CompanionDeviceReceiver
from device_pairing import PairingCandidate
from desktop_owner_confirmation import confirmation_record
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError

_REVIEW = object()


class NativeScopeReview:
    def __init__(self, seal, owner, candidate, record, summary):
        if seal is not _REVIEW:
            raise TypeError("Use the native owner scope-review producer")
        self.owner, self.candidate, self.summary = owner, candidate, summary
        self._record = protocol.canonical_bytes(record)
        self.digest = hashlib.sha256(self._record).hexdigest()

    @property
    def record(self):
        return json.loads(self._record)


class NativeCommittedRecoveryReview(NativeScopeReview):
    """Local original-SQL review only; it is NOT a pairing/transport permission."""


class NativePairingConfig:
    """Local owner settings CAS, audited in the original operation/job store."""
    def __init__(self, manager, config_path, raw, *, current_installation=lambda: None):
        if manager.platform.name != "windows" or not isinstance(raw, bytes):
            raise PlatformError("Native Windows owner settings storage required")
        from windows_installation import _object
        self.manager, self.backend, self.path, self.raw = manager, manager.platform, Path(config_path), raw
        self.sid = self.backend.logon_context()["userSid"]
        self.value = _object(raw)
        self.current_installation = current_installation
        self.current()

    def current(self):
        if self.manager.closed:
            raise PlatformError("Original owner settings manager is closed; no late settings replacement")
        self.current_installation()
        if self.backend.read_private_bytes(str(self.path.parent), self.path.name, self.sid) != self.raw:
            raise PlatformError("Owner configuration changed; close and review the current native settings")

    def enable_after_pairing(self, host, control_port, bulk_port, *, desktop, identity_dir):
        self.current()
        value = copy.deepcopy(self.value)
        devices = copy.deepcopy(value.get("devices", {}))
        configured_identity = devices.get("identityDir")
        if configured_identity is not None and ntpath.normcase(str(configured_identity)) != ntpath.normcase(str(identity_dir)):
            raise PlatformError("Reviewed existing native device identity differs from the registered configuration")
        devices.update(enabled=True, identityDir=str(identity_dir), listen={"host": host, "controlPort": control_port, "bulkPort": bulk_port})
        value["devices"] = devices
        if desktop:
            # The installed launcher alone supplies the verified CURRENT binary
            # and hash. Never write a page/env/version path into user config.
            value["desktop"] = {**value.get("desktop", {}), "enabled": True}
        after = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        operation, job_id, now = "owner-pairing-" + uuid.uuid4().hex, str(uuid.uuid4()), self.manager.now()
        job = {"id": job_id, "operationId": operation, "worker": "native_owner_settings", "status": "running", "environmentId": "native-owner-state",
               "createdAt": now, "updatedAt": now, "output": "", "outputTruncated": False, "exitCode": None,
               "message": "Original owner approved paired-channel settings; no old work is resumed", "remoteEffectsVerified": False}
        details = {"protocol": "native-owner-settings-v1", "beforeSha256": hashlib.sha256(self.raw).hexdigest(),
                   "afterSha256": hashlib.sha256(after).hexdigest(), "noAutomaticReplay": True}
        with self.manager.lock:
            self.current()
            self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                        (operation, details["afterSha256"], job_id, "running", now, json.dumps(details)))
            self.manager.ledger.commit()
            self.manager.jobs[job_id] = job
            self.manager._save(job)
        def intent(facts):
            with self.manager.lock:
                details["nativeReplacement"] = facts
                self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(details), operation))
                self.manager.ledger.commit()
        try:
            result = self.backend.compare_and_swap(str(self.path.parent), self.path.name, self.raw, after, self.current, record_intent=intent)
            with self.manager.lock:
                details["nativeResult"] = result
                self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(details), operation))
                job.update(status="completed" if result.get("verified") is True else "unknown", updatedAt=self.manager.now())
                self.manager._save(job)
            if result.get("verified") is not True:
                raise PlatformError("Pairing is committed, but settings replacement is unknown; inspect original owner operation " + operation)
            self.raw, self.value = after, value
            self.manager.config["devices"] = copy.deepcopy(devices)
            if desktop:
                self.manager.config.setdefault("desktop", {})["enabled"] = True
            return {"operationId": operation, "settingsVerified": True, "businessListenerStarted": False}
        except BaseException:
            with self.manager.lock:
                if job["status"] != "completed":
                    job.update(status="unknown", updatedAt=self.manager.now(), message="Inspect this original owner settings operation; no automatic replacement replay")
                    self.manager._save(job)
            raise


class PairingOwnerController:
    def __init__(self, manager, local_identity, config_store):
        if not isinstance(local_identity, device_identity.LocalIdentity):
            raise TypeError("Load the existing native local identity first")
        self.manager, self.identity, self.config_store = manager, local_identity, config_store
        self.receiver = CompanionDeviceReceiver(manager, local_identity, native_management_only=True)
        self.coordinator = self.receiver.pairing
        self.lock = threading.RLock()
        self.invitation = self.pending = self.review = None
        self.completed = False
        self.settings_receipt = None
        self._settings_attempted = False
        self.error = None
        self.endpoint = None
        self.recovery_response_prepared = False

    def _workspace_contains_private_state(self, binding):
        root = ntpath.normcase(ntpath.normpath(self.manager._environment(binding["environmentId"])["root"]))
        private = [str(self.manager.state), str(self.config_store.path.parent), str(self.identity.directory)]
        for item in private:
            try:
                if ntpath.commonpath([root, ntpath.normcase(ntpath.normpath(item))]) == root:
                    return True
            except ValueError:
                pass
        return False

    def scope_options(self):
        options = []
        for capability in self.receiver.describe()["capabilities"]:
            if capability["version"] == "workspace-v1" and self._workspace_contains_private_state(capability["resources"][0]):
                continue
            options.append(copy.deepcopy(capability))
        settings, health = self.manager.config.get("desktop", {}), WindowsDesktopHealth().probe()
        if (not any(item["id"] == "desktop" for item in options) and health.get("available") is True
                and isinstance(settings.get("binary"), str) and Path(settings["binary"]).is_file()
                and isinstance(settings.get("sha256"), str) and protocol.DIGEST.fullmatch(settings["sha256"])):
            options.append({"id": "desktop", "version": "desktop-v2", "resources": [{"kind": "desktop", "sessionId": health["sessionId"], "displayId": "primary"}],
                            "toolDeclarations": [{"toolName": "desktop.observe", "effect": "read"}, {"toolName": "desktop.act", "effect": "write"}, {"toolName": "desktop.control", "effect": "write"}]})
        return options

    def create_invitation(self, host, candidate_port, completion_port, control_port, bulk_port, *, expires_at_ms=None):
        if self.invitation is not None:
            raise PlatformError("This owner window already issued its one-time invitation; do not silently replace it")
        self.config_store.current()
        hint = protocol.validate_connection_hint({"kind": "lan", "host": host, "candidatePort": candidate_port,
                                                  "completionPort": completion_port, "controlPort": control_port, "bulkPort": bulk_port})
        self.endpoint = hint
        now = self.manager.now()
        ttl = 120000 if expires_at_ms is None else expires_at_ms - now
        protocol.integer(ttl, "invitationTtl", 1000, 120000)
        self.invitation = self.coordinator.create_invitation(self.identity, None, now, ttl_ms=ttl, connection_hint=hint)
        return copy.deepcopy(self.invitation)

    def inspect(self, raw):
        if self.invitation is None or protocol.parse_json_object(raw).get("inviteId") != self.invitation["inviteId"]:
            raise PlatformError("Candidate is not for this current native owner invitation")
        candidate = self.coordinator.inspect_candidate(self.identity, raw, self.manager.now())
        return protocol.canonical_bytes(self.coordinator.candidate_receipt(candidate, self.manager.now()))

    def prepare_review(self, candidate, selected_ids, tool_budget, minutes, history_read=False):
        self.config_store.current()
        if not isinstance(candidate, PairingCandidate) or self.invitation is None or candidate.invite_id != self.invitation["inviteId"]:
            raise PlatformError("Current native verified invitation candidate is required")
        protocol.integer(tool_budget, "toolBudget", 1, 400); protocol.integer(minutes, "minutes", 1, 60)
        if type(history_read) is not bool or not isinstance(selected_ids, list) or not selected_ids or len(set(selected_ids)) != len(selected_ids):
            raise PlatformError("Explicit current native scope selections are required")
        available = {item["id"]: item for item in self.scope_options()}
        if any(key not in available for key in selected_ids):
            raise PlatformError("Selected native scope disappeared; review current capabilities")
        selected = [available[key] for key in selected_ids]
        tools = sorted({tool["toolName"] for item in selected for tool in item["toolDeclarations"]})
        resources = [binding for item in selected for binding in item["resources"]]
        now = self.manager.now()
        grant = {"grantId": "owner-" + uuid.uuid4().hex, "peerDeviceId": candidate.device_id, "localDeviceId": self.identity.device_id,
                 "authorizationEpoch": 1, "issuedAt": now, "expiresAt": now + minutes * 60000,
                 "capabilities": ["tool.invoke", "operation.get", "operation.cancel", "artifact.describe", "artifact.readChunk"],
                 "toolNames": tools, "resources": resources, "allowedOrigins": [candidate.device_id], "allowDelegation": False,
                 "maxDelegationDepth": 0, "toolBudget": tool_budget, "modelBudget": 0}
        if "desktop" in selected_ids:
            grant["capabilities"].append("desktop.viewer")
        if history_read:
            grant["historyRead"] = {"expiresAt": now + 86400000, "methods": ["operation.get", "artifact.describe", "artifact.readChunk"]}
        if len(protocol.canonical_bytes(grant)) > 8192:
            raise PlatformError("Select fewer exact scopes; the complete owner grant must fit its original reviewed bound")
        record = {"fingerprints": candidate.display(), "grant": grant, "selectedCapabilities": selected,
                  "workspacePaths": {item["id"]: self.manager._environment(item["resources"][0]["environmentId"])["root"] for item in selected if item["version"] == "workspace-v1"},
                  "endpoint": self.endpoint, "enableDesktop": "desktop" in selected_ids}
        summary = ("仅下列资源和工具；电脑不运行第二Agent/Goal，不需要Codex，模型额度为0。\r\n"
                   "主屏desktop授权覆盖当前交互会话的所有窗口，不是文件沙箱；命令也不是OS沙箱。\r\n"
                   + ("本次桌面授权包含手机实时查看电脑画面，以及人工鼠标和键盘接管；交还控制权后，AI必须重新观察再操作。\r\n" if "desktop.viewer" in grant["capabilities"] else "")
                   + "这不会恢复任何旧任务。完整指纹、地址、额度和期限：\r\n" + json.dumps(record, ensure_ascii=False, indent=2))
        if len(summary) > 16000:
            raise PlatformError("Select fewer scopes so the COMPLETE review fits the native confirmation window")
        return NativeScopeReview(_REVIEW, self, candidate, record, summary)

    def confirm(self, review, proof):
        if type(review) is not NativeScopeReview or review.owner is not self or self.pending is not None:
            raise PlatformError("Only this original native scope review may be confirmed")
        confirmation_record(proof, purpose="device_pairing", summary=review.summary)
        if not 0 <= self.manager.now() - proof.confirmed_at <= 30000:
            raise PlatformError("Physical local scope confirmation is not fresh")
        self.config_store.current()
        # Re-read native configuration/resources after the physical dialog;
        # its click/JSON cannot swap the reviewed target or scope afterward.
        current = {item["id"]: item for item in self.scope_options()}
        if any(item["id"] not in current or protocol.canonical_bytes(item) != protocol.canonical_bytes(current[item["id"]]) for item in review.record["selectedCapabilities"]):
            raise PlatformError("Native resource changed during owner review; no authority granted")
        pending = self.coordinator.confirm_candidate_locally(self.identity, review.candidate, True, self.manager.now(), reviewed_owner_grant=review.record["grant"])
        self.review, self.pending = review, pending
        return pending

    def complete(self, actual_peer, raw):
        with self.lock:
            if self.pending is None or self.review is None:
                raise PlatformError("Physical owner confirmation is missing")
            result = self.coordinator.finalize_candidate_pairing(self.pending, actual_peer, raw, self.review.record["grant"], self.manager.now())
            self.completed = True
            if not self._settings_attempted:
                self._settings_attempted = True  # A lost response never repeats owner settings CAS.
                try:
                    self.settings_receipt = self.config_store.enable_after_pairing(self.endpoint["host"], self.endpoint["controlPort"], self.endpoint["bulkPort"], desktop=self.review.record["enableDesktop"], identity_dir=self.identity.directory)
                except Exception:
                    self.error = "设备已配对，但本机设置写入未确认。检查原本机operation；不要自动重试或重放旧任务。"
            return protocol.canonical_bytes(result)

    def close(self):
        self.receiver.close()

    def recovery_candidates(self):
        self.config_store.current()
        return self.coordinator.committed_pairing_recovery_candidates(self.manager.now(), limit=8)

    def prepare_recovery_review(self, selected):
        if self.invitation is not None or self.pending is not None:
            raise PlatformError("Close the current invitation before selecting a separate original-commit recovery")
        rows = self.recovery_candidates()
        if not any(protocol.canonical_bytes(row) == protocol.canonical_bytes(selected) for row in rows):
            raise PlatformError("Select a current native original committed transaction, not JSON authority")
        # Only the unique native producer decides whether current owner scope,
        # epoch, identity and expiry still allow this read. This preview creates
        # no row, permission, grant, network listener or replacement Pending.
        self.coordinator.committed_pairing_recovery(self.identity, selected["inviteId"], selected["candidateDigest"], self.manager.now())
        completion = self.coordinator.store.committed_completion(selected["inviteId"])
        record = {"originalCommit": selected, "localFingerprint": self.identity.certificate_fingerprint,
                  "originalGrant": completion["grantReceipt"]["body"]["grant"],
                  "completionDigest": hashlib.sha256(protocol.canonical_bytes(completion)).hexdigest()}
        summary = ("仅恢复下面已提交事务的原回执；不会写配置、重新签授、续期、增额或自动启动业务。\r\n"
                   "将新通道地址手动交给同一手机的显式恢复入口；地址本身不授信，仍核对双向TLS原指纹。\r\n" + json.dumps(record, ensure_ascii=False, indent=2))
        if len(summary) > 16000:
            raise PlatformError("Complete original committed scope does not fit native review; no clipped confirmation")
        return NativeCommittedRecoveryReview(_REVIEW, self, None, record, summary)

    def confirm_recovery(self, review, proof):
        if not isinstance(review, NativeCommittedRecoveryReview) or review.owner is not self or self.invitation is not None:
            raise PlatformError("Only the current local original-commit review can open a read-only recovery")
        confirmation_record(proof, purpose="device_pairing_recovery", summary=review.summary)
        if not 0 <= self.manager.now() - proof.confirmed_at <= 30000:
            raise PlatformError("Original-commit local recovery confirmation is not fresh")
        self.config_store.current()
        fixed = review.record["originalCommit"]
        completion = self.coordinator.store.committed_completion(fixed["inviteId"])
        if completion is None or hashlib.sha256(protocol.canonical_bytes(completion)).hexdigest() != review.record["completionDigest"]:
            raise PlatformError("Original immutable committed result changed during owner review")
        return self.coordinator.committed_pairing_recovery(self.identity, fixed["inviteId"], fixed["candidateDigest"], self.manager.now(), ttl_ms=30000)

    def recover(self, native_recovery, actual_peer, raw):
        result = self.coordinator.recover_candidate_pairing(native_recovery, actual_peer, raw, self.manager.now())
        self.recovery_response_prepared = True  # NOT proof that the phone saved/accepted the response.
        return protocol.canonical_bytes(result)


class PairingLanHost:
    """Owner-window lifetime only; isolated bootstrap and native reserved endpoints.

    The transport domain owns every TLS engine, frame, reservation and listener.
    This class just supplies the original coordinator callbacks and transfers
    exact control/bulk sockets once to the original Companion service.
    """
    def __init__(self, controller):
        self.controller = controller
        self.loop = self.thread = None
        self.listeners, self.reservations = [], {}
        self.completion = self.completion_ready = None
        self.ready = threading.Event()
        self.failure = None
        self.closed = False
        self._close_requested = False
        self._completion_work = set()
        self.recovery = self.recovery_address = None

    def start(self, host, *, control_port=8768, bulk_port=8769):
        host = self._literal_host(host)
        for value in (control_port, bulk_port):
            protocol.integer(value, "businessPort", 0, 65535)
        self._launch(lambda: self._start(host, control_port, bulk_port))
        return copy.deepcopy(self.controller.invitation)

    @staticmethod
    def _literal_host(host):
        try:
            ip = ipaddress.ip_address(host)
            if ip.is_unspecified or ip.is_multicast or "%" in host:
                raise ValueError()
            host = ip.compressed
        except ValueError as error:
            raise PlatformError("Enter this computer's reachable numeric LAN address, not a public relay URL or 0.0.0.0") from error
        return host

    def _launch(self, starter):
        if self.thread is not None or self.closed:
            raise PlatformError("This native owner channel was already started; no silent replacement/rebinding")
        def run():
            self.loop = asyncio.new_event_loop(); asyncio.set_event_loop(self.loop)
            try:
                self.loop.run_until_complete(starter())
            except BaseException as error:
                self.failure = type(error).__name__
                self.loop.run_until_complete(self._stop())
                self.loop.run_until_complete(self.loop.shutdown_default_executor())
                self.ready.set(); self.loop.close(); return
            self.ready.set()
            self.loop.run_forever()
            self.loop.run_until_complete(self._stop())
            self.loop.run_until_complete(self.loop.shutdown_asyncgens())
            self.loop.run_until_complete(self.loop.shutdown_default_executor())
            self.loop.close()
        self.thread = threading.Thread(target=run, name="pocket-owner-pairing", daemon=True)
        self.thread.start()
        if not self.ready.wait(15) or self.failure:
            self.close()
            raise PlatformError("Native pairing endpoints could not be reserved; no invitation/unsafe fallback listener was published")

    def start_recovery(self, host, review, proof):
        host = self._literal_host(host)
        native_recovery = self.controller.confirm_recovery(review, proof)
        self._launch(lambda: self._start_recovery(host, native_recovery))
        return self.recovery_address

    async def _start_recovery(self, host, native_recovery):
        import device_transport as transport
        import device_transport_pairing as bootstrap
        self.recovery = native_recovery
        self.completion = await bootstrap.reserve_completion_recovery_lan(host, native_recovery=native_recovery)
        async def recover(peer, raw):
            if self._close_requested:
                raise PlatformError("Original committed-result channel is closing")
            work = asyncio.create_task(asyncio.to_thread(self.controller.recover, native_recovery, peer, raw))
            self._completion_work.add(work); work.add_done_callback(self._completion_work.discard)
            return await asyncio.shield(work)
        listener = await bootstrap.listen_completion_recovery_lan(
            context=transport.native_device_context(self.controller.identity, [native_recovery.peer_certificate_der], server_side=True),
            native_recovery=native_recovery, completion_handler=recover, reservation=self.completion)
        self.listeners.append(listener)
        self.recovery_address = self.completion.address

    async def _start(self, host, control_port, bulk_port):
        import device_transport as transport
        import device_transport_pairing as bootstrap
        expires = self.controller.manager.now() + 120000
        self.completion_ready = asyncio.Event()
        self.reservations["control"] = await transport.reserve_lan_port(host, control_port, lane="control", owner_enabled=True, expires_at_ms=expires)
        self.reservations["bulk"] = await transport.reserve_lan_port(host, bulk_port, lane="bulk", owner_enabled=True, expires_at_ms=expires)
        self.completion = await bootstrap.reserve_completion_lan(host, owner_enabled=True, invitation_expires_at_ms=expires)
        async def candidate(raw):
            # Pending owner review returns immediately. Only after an actual
            # native confirmation may we briefly await its listener handoff,
            # preventing a confirmed receipt racing an as-yet non-listening port.
            if self.controller.pending is not None and not self.completion_ready.is_set():
                await asyncio.wait_for(self.completion_ready.wait(), 3)
            return self.controller.inspect(raw)
        listener = await bootstrap.listen_candidate_lan(host, 0, context=bootstrap.candidate_server_context(self.controller.identity), owner_enabled=True,
                                                        invitation_expires_at_ms=expires, candidate_handler=candidate)
        self.listeners.append(listener)
        candidate_address = listener.sockets[0].getsockname()
        self.controller.create_invitation(candidate_address[0], candidate_address[1], self.completion.address[1],
                                          self.reservations["control"].address[1], self.reservations["bulk"].address[1], expires_at_ms=expires)

    def confirm(self, review, proof):
        if self.closed or self.loop is None:
            raise PlatformError("Original native pairing channel is unavailable")
        pending = self.controller.confirm(review, proof)
        future = asyncio.run_coroutine_threadsafe(self._listen_completion(pending), self.loop)
        try:
            future.result(10)
        except BaseException as error:
            raise PlatformError("Owner confirmation retained, but the original reserved completion channel is unavailable; no business authority was issued") from error
        return pending

    async def _listen_completion(self, pending):
        import device_transport as transport
        import device_transport_pairing as bootstrap
        async def complete(peer, raw):
            if self._close_requested:
                raise PlatformError("Original owner pairing channel is closing; no new completion dispatched")
            # Closing a TLS channel cancels its coroutine, NOT an already
            # dispatched native SQL/CAS worker. Keep that worker until it ends
            # before reporting host closure or allowing the original DB to close.
            work = asyncio.create_task(asyncio.to_thread(self.controller.complete, peer, raw))
            self._completion_work.add(work)
            work.add_done_callback(self._completion_work.discard)
            return await asyncio.shield(work)
        listener = await bootstrap.listen_completion_lan(context=transport.native_device_context(self.controller.identity, [pending.peer_certificate_der], server_side=True),
                                                         native_pending=pending, completion_handler=complete, reservation=self.completion)
        self.listeners.append(listener)
        self.completion_ready.set()

    def business_reservations(self):
        if (not self.controller.completed or not self.controller.settings_receipt or not self.controller.settings_receipt.get("settingsVerified")
                or self.closed or any(not item.is_reserved for item in self.reservations.values())):
            raise PlatformError("Pairing/settings are not confirmed or the original endpoints expired; no automatic rebinding")
        return dict(self.reservations)

    async def _stop(self):
        await asyncio.gather(*(item.close() for item in self.listeners), return_exceptions=True)
        if self.completion is not None:
            await self.completion.close()
        await asyncio.gather(*(item.close() for item in self.reservations.values()), return_exceptions=True)
        if self._completion_work:
            await asyncio.gather(*tuple(self._completion_work), return_exceptions=True)

    def close(self):
        if self.closed:
            return
        self._close_requested = True
        if self.loop is not None and not self.loop.is_closed() and self.thread is not threading.current_thread():
            self.loop.call_soon_threadsafe(self.loop.stop)
            if self.thread is not None:
                self.thread.join(timeout=15)
                if self.thread.is_alive():
                    raise PlatformError("Original native pairing host did not confirm closure")
        self.closed = True


def run_native_pairing(config_path, *, installed=None, launch_config=None, identity_dir=None):
    """Called only after the existing user-session mutex was acquired by --pairing."""
    if os.name != "nt" or WindowsDesktopHealth().probe().get("available") is not True:
        raise PlatformError("Native pairing requires the actual interactive Windows owner session")
    from platform_windows import WindowsBackend
    from windows_installation import _object
    from pocket_companion import JobManager, serve_original_manager
    from device_companion import DeviceCompanionService
    from desktop_pairing_window import show_pairing_window
    backend = WindowsBackend()
    path = Path(config_path)
    sid = backend.logon_context()["userSid"]
    if installed is not None:
        if launch_config is None:
            raise PlatformError("The installed entry must supply its verified current native configuration")
        launch_config.check_current()
        raw = installed._raw(installed.state, "companion.json", 262144)
        value = launch_config.json()
        identity_dir = installed.identity_dir
        installation_current = installed.check_current_pointer
    else:
        raw = backend.read_private_bytes(str(path.parent), path.name, sid)
        if raw is None:
            raise PlatformError("Explicit development configuration is missing")
        value = _object(raw)
        identity_dir = identity_dir or value.get("devices", {}).get("identityDir")
        installation_current = lambda: None
    if not identity_dir:
        raise PlatformError("Initialize an existing native device identity explicitly before pairing; this entry never provisions keys")
    local = device_identity.load_existing_identity(identity_dir)
    token_path = Path(value["tokenFile"])
    if not token_path.is_absolute():
        raise PlatformError("Original owner token must be a registered absolute private path")
    token = backend.read_private_bytes(str(token_path.parent), token_path.name, sid, maximum=512)
    if token is None:
        raise PlatformError("Original private Companion token is missing; no fallback device authority")
    manager = JobManager(value, token.decode("utf-8").strip())
    controller = host = None
    try:
        config_store = NativePairingConfig(manager, path, raw, current_installation=installation_current)
        controller = PairingOwnerController(manager, local, config_store)
        host = PairingLanHost(controller)
        start = show_pairing_window(controller, host)
        if not start:
            return {"businessListenerStarted": False, "paired": controller.completed}
        config_store.current()
        reservations = host.business_reservations()
        controller.close()  # Metadata-only owner view never created a GUI worker.
        manager.device_companion = DeviceCompanionService(manager, local_identity=local, native_lan_reservations=reservations)
        manager.device_companion.start()  # Same original manager/ledger, exact transferred sockets.
        host.close()  # Consumed sockets now belong only to the original service.
        serve_original_manager(manager)
        return {"businessListenerStarted": True, "paired": True}
    finally:
        if host is not None:
            host.close()
        if controller is not None:
            controller.close()
        if not manager.closed:
            manager.close()
