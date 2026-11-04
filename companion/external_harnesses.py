"""Probe and revision admission for owner-managed harnesses on existing jobs."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import re

from codex_driver import DriverError
from generic_acp import AcpDriver
import harness_registry as registry
from platform_backend import PlatformError
from platform_windows import WindowsJobProcess


class ExternalHarnesses:
    def __init__(self, manager):
        self.manager = manager
        self.observations = {}
        self.retained_probes = []

    @staticmethod
    def _driver(worker):
        if worker == "muse_msp":
            from muse_driver import MuseDriver
            return MuseDriver
        return AcpDriver

    def probe(self, request, env, error):
        manager, worker = self.manager, request.get("worker")
        if env.get("kind", "local") != "local":
            raise error("External harness protocols require a Companion on the execution computer", 409)
        if request.get("environmentBinding") != manager.environment_binding(env["id"]):
            raise error("Harness probe requires the original environment binding", 409)
        if any(k in request for k in ("argv", "command", "args", "env", "threadId", "resumeJobId")):
            raise error("A harness probe cannot supply a launch command or existing session", 409)
        for key in ("model", "modeId"):
            if key in request and (not isinstance(request[key], str) or not request[key] or len(request[key]) > 200):
                raise error("Invalid probe " + key)
        config = registry.configuration(env, worker)
        binding = manager.environment_binding(env["id"])
        revision = registry.revision(env, worker, manager.auth_generation_salt)
        result = {"worker": worker, "environmentId": env["id"], "environmentBinding": binding,
                  "label": config["label"], "adapter": config["adapter"], "protocol": config["protocol"],
                  "configurationRevision": revision, "executionPolicy": "owner_managed",
                  "available": False, "protocolVerified": False, "sessionAccessVerified": False,
                  "accountConfigured": None, "modelInferenceVerified": False,
                  "capabilities": registry.capabilities(worker), "agentInfo": {},
                  "auth": {"configured": None, "methodIds": [], "selectedMethod": config.get("authMethod")}}
        if not manager.probe_slots.acquire(blocking=False):
            raise error("Worker capability probes are busy", 429)
        process = driver = None
        temporary = None
        try:
            argv = registry.command(env, worker)
            base = manager.state / "harness-probes"
            base.mkdir(mode=0o700, exist_ok=True)
            temporary = Path(tempfile.mkdtemp(prefix="probe-", dir=base))
            process = manager._spawn({**env, "root": str(temporary)}, argv, str(temporary),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                env=registry.child_environment(env, worker, os.environ))
            driver = self._driver(worker)(process, str(temporary), lambda _state: None,
                lambda: manager.closed, 20, auth_method=config.get("authMethod"),
                redact=lambda value: registry.redact(env, worker, value), worker=worker)
            hello = driver.probe(create_session=True)
            driver._select("model", request.get("model"))
            driver._select("mode", request.get("modeId"))
            if manager.environment_binding(env["id"]) != binding or registry.revision(env, worker, manager.auth_generation_salt) != revision:
                raise DriverError("harness_configuration_changed")
            options = driver.session_options
            capability_revision = registry.digest([config["protocol"], revision, hello, options,
                request.get("model"), request.get("modeId")])
            observed = {"worker": worker, "environmentId": env["id"], "binding": binding,
                "configurationRevision": revision, "capabilityRevision": capability_revision,
                "hello": hello, "options": options, "model": request.get("model"), "modeId": request.get("modeId"),
                "expiresAt": time.monotonic() + 600}
            result.update(available=True, protocolVerified=True, sessionAccessVerified=driver.session_id is not None,
                capabilityRevision=capability_revision, agentInfo=hello.get("agentInfo", {}),
                hello=hello, sessionOptions=options, models=options.get("models", {}), modes=options.get("modes", {}),
                reportedModel=driver.reported_model, modeId=driver.mode_id,
                capabilities=registry.capabilities(worker, hello, team_tools=worker != "muse_msp" or "sessionMcp" in hello.get("grantedCapabilities", [])),
                auth={"configured": None, "methodIds": [m["id"] for m in hello.get("authMethods", [])],
                      "selectedMethod": config.get("authMethod")},
                message=("Installed protocol and session access observed without sending a model prompt" if driver.session_id
                         else "Installed protocol and model catalogue observed; session access and model inference remain unverified"))
            self.observations[(env["id"], worker, capability_revision)] = observed
            result["harnessSelection"] = {"protocol": registry.SELECTION_PROTOCOL, "workerId": worker,
                "configurationRevision": revision, "capabilityRevision": capability_revision,
                "ownerManagedExecution": True}
        except (OSError, ValueError, DriverError, PlatformError) as failure:
            code = "model_or_mode_unconfirmed" if "option" in str(failure) else "protocol_or_session_unavailable"
            result.update(reason={"code": code, "message": "The installed harness did not confirm this protocol/session/selection. Check its owner configuration and sign-in on the execution computer."})
            if driver is not None and driver.protocol_verified and driver.hello is not None:
                hello = driver.hello
                result.update(protocolVerified=True, hello=hello, agentInfo=hello.get("agentInfo", {}),
                    auth={"configured": None, "methodIds": [m["id"] for m in hello.get("authMethods", [])], "selectedMethod": config.get("authMethod")})
        finally:
            try:
                if driver is not None:
                    driver.close_steering()
                if process is not None:
                    proof = manager._terminate(process)
                    if proof.get("processGroupStoppedVerified") is not True:
                        self.retained_probes.append((process, temporary))
                        temporary = None
                        result.update(available=False, reason={"code": "probe_cleanup_unconfirmed", "message": "The original probe process group has not confirmed exit"})
                    else:
                        for stream in (process.stdin, process.stdout):
                            if stream is not None:
                                stream.close()
                        if isinstance(process, WindowsJobProcess):
                            process.close()
                if temporary is not None:
                    shutil.rmtree(temporary)
            finally:
                manager.probe_slots.release()
        if not result["available"] and result.get("capabilityRevision"):
            self.observations.pop((env["id"], worker, result["capabilityRevision"]), None)
            result.pop("harnessSelection", None)
        now = time.monotonic()
        self.observations = {key: value for key, value in self.observations.items() if value["expiresAt"] > now}
        return registry.redact(env, worker, result)

    def selection(self, request, env, error):
        worker = request.get("worker")
        config = registry.configuration(env, worker)
        if (env.get("kind", "local") != "local" or not env.get("writeAllowed") or request.get("readOnly") is not False
                or request.get("externalExecutionRiskAcknowledged") is not True):
            raise error("External harnesses require the owner's local writable process policy and explicit execution acknowledgement", 409)
        if any(key in request for key in ("argv", "command", "args", "env", "backendSelection", "teamReadOnlyTools")):
            raise error("The request cannot replace the owner's harness command, credentials or execution policy", 409)
        selection = request.get("harnessSelection")
        fields = {"protocol", "workerId", "configurationRevision", "capabilityRevision", "ownerManagedExecution"}
        if (not isinstance(selection, dict) or set(selection) != fields or selection.get("protocol") != registry.SELECTION_PROTOCOL
                or selection.get("workerId") != worker or selection.get("ownerManagedExecution") is not True
                or any(not isinstance(selection.get(k), str) or not re.fullmatch(r"[a-f0-9]{64}", selection[k]) for k in ("configurationRevision", "capabilityRevision"))):
            raise error("A harness job requires its exact original probe selection", 409)
        observed = self.observations.get((env["id"], worker, selection.get("capabilityRevision")))
        if observed is None:
            raise error("Harness observation is missing; probe the original selection again", 409)
        if (selection.get("configurationRevision") != observed["configurationRevision"]
                or request.get("environmentBinding") != observed["binding"]
                or request.get("model") != observed["model"] or request.get("modeId") != observed["modeId"]):
            raise error("Harness job differs from the original environment/model/mode observation", 409)
        self.current(observed, require_unexpired=True, error=error)
        return {"observed": observed, "config": config, "selection": json.loads(json.dumps(selection))}

    def current(self, observed, *, require_unexpired=False, error=DriverError):
        manager = self.manager
        env = manager._environment(observed["environmentId"])
        if (manager.closed or manager.environment_binding(env["id"]) != observed["binding"]
                or registry.revision(env, observed["worker"], manager.auth_generation_salt) != observed["configurationRevision"]
                or require_unexpired and observed["expiresAt"] <= time.monotonic()):
            raise error("Original harness executable/configuration/environment observation changed")

    def close(self):
        for process, temporary in self.retained_probes:
            proof = self.manager._terminate(process, hard=True)
            if proof.get("processGroupStoppedVerified") and temporary is not None:
                shutil.rmtree(temporary, ignore_errors=True)
            if isinstance(process, WindowsJobProcess):
                process.close()
        self.retained_probes.clear()
        self.observations.clear()
