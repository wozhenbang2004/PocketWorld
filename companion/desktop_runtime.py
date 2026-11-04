"""Small wiring seam for the original Companion and the shared device handler."""
from pathlib import Path

from cua_desktop_adapter import McpCuaDriver
from agent_desktop_driver import AgentDesktopCli, AgentDesktopDriver
from desktop_adapter import DesktopAdapter, DesktopError
from desktop_jobs import DesktopJobs
from desktop_sessions import WindowsDesktopHealth
from desktop_stream import DesktopStreamService
from desktop_viewer import DesktopViewer
from platform_backend import get_backend


class UnavailableDesktop:
    def __init__(self, reason):
        self.reason = reason

    def describe(self):
        return {"available": False, "reason": self.reason, "capabilityVersion": "desktop-v2",
                "requiresExternalAgent": False, "modelRequired": False, "nativeGuiVerified": False}

    def invoke(self, envelope, permission):
        raise DesktopError(self.reason, 409)

    query = invoke
    viewer = invoke

    def close(self):
        return {"notStarted": True, "processGroupStoppedVerified": True}


class DesktopRuntime:
    def __init__(self, jobs, *, streams=None):
        self.jobs = jobs
        self.viewers = DesktopViewer(jobs, streams=streams)

    def describe(self):
        value = self.jobs.adapter.describe()
        value["originalBudgetConnected"] = callable(self.jobs.budget_claim)
        value["receivingBudgetPolicy"] = self.jobs.native_budget.describe()
        value["streaming"] = self.viewers.describe()
        if not value["originalBudgetConnected"]:
            value.update(available=False, reason="original_task_budget_reservation_not_connected")
        return value

    def invoke(self, envelope, permission):
        return self.jobs.invoke(envelope, permission)

    def query(self, envelope, permission):
        return self.jobs.query(envelope, permission)

    def cancel(self, job_id):
        return self.jobs.cancel(job_id)

    def viewer(self, envelope, permission):
        return self.viewers.invoke(envelope, permission)

    def close(self):
        return self.jobs.close()


def attach_desktop(manager, local_identity, artifact_store, *, budget_claim=None, config=None):
    """Attach once after the original identity, budget and artifact owners exist.

    artifact_store.current must enforce the current owner grant/task audience on
    every publication and chunk read. No service or token-to-page adapter is
    created here. This function does not change the original HTTP(S) listener.
    """
    settings = manager.config.get("desktop", {}) if config is None else config
    if not isinstance(settings, dict) or settings.get("enabled") is not True:
        return UnavailableDesktop("desktop_explicitly_disabled")
    backend = get_backend()
    if backend.name != "windows":
        return UnavailableDesktop("windows_interactive_user_session_required; existing POSIX command/files remain independent")
    if set(settings) - {"enabled", "binary", "sha256", "agentDesktop", "streaming"}:
        return UnavailableDesktop("unknown_desktop_runtime_configuration")
    binary, sha256 = settings.get("binary"), settings.get("sha256")
    if not isinstance(binary, str) or not Path(binary).is_absolute() or not isinstance(sha256, str):
        return UnavailableDesktop("pinned_driver_artifact_not_configured")
    semantic = settings.get("agentDesktop")
    if semantic is not None and (not isinstance(semantic, dict) or set(semantic) != {"binary", "sha256"}
            or not isinstance(semantic["binary"], str) or not Path(semantic["binary"]).is_absolute()
            or not isinstance(semantic["sha256"], str)):
        return UnavailableDesktop("pinned_agent_desktop_artifact_not_configured")
    if artifact_store.source_device_id != local_identity.device_id:
        return UnavailableDesktop("artifact_source_device_identity_mismatch")
    viewer_settings = settings.get("streaming", {})
    if (not isinstance(viewer_settings, dict) or set(viewer_settings) - {"enabled", "iceServers", "fps", "maxEdge"}
            or type(viewer_settings.get("enabled", True)) is not bool):
        return UnavailableDesktop("unknown_desktop_viewer_configuration")
    try:
        streams = DesktopStreamService(WindowsDesktopHealth(), ice_servers=viewer_settings.get("iceServers", []),
                                       fps=viewer_settings.get("fps", 10), max_edge=viewer_settings.get("maxEdge", 1200))
    except Exception:
        return UnavailableDesktop("invalid_desktop_viewer_configuration")
    from desktop_recovery import apply_pending_local_request
    try:
        apply_pending_local_request(manager, local_identity, backend)
    except Exception:
        # A malformed/stale local approval blocks GUI, not unrelated explicit
        # command/file capability. It is never downgraded to auto-recovery.
        return UnavailableDesktop("local_recovery_approval_invalid_or_unsettled; inspect original local recovery receipt")
    revision = 0
    def capture_store(raw, metadata, expires):
        nonlocal revision
        # The only outgoing artifact schema is the identity-domain contract.
        original = artifact_store.register_snapshot(raw, metadata, expires)
        revision += 1
        return artifact_store.wire_descriptor(original, instance_id=local_identity.instance_id, revision=revision)
    driver = McpCuaDriver(binary, sha256, backend, private_state=manager.state / "cua-driver")
    if semantic is not None:
        driver = AgentDesktopDriver(driver, AgentDesktopCli(semantic["binary"], semantic["sha256"], backend,
                                                           private_state=manager.state / "agent-desktop"))
    adapter = DesktopAdapter(manager, driver, WindowsDesktopHealth(), capture_store, local_identity.device_id)
    runtime = DesktopRuntime(DesktopJobs(manager, adapter, budget_claim=budget_claim), streams=streams)
    if viewer_settings.get("enabled", True) is False:
        runtime.viewers.close()
    return runtime
