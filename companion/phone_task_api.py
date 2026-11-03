"""Authenticated loopback adapter for the original Companion HTTP listener."""
import ipaddress
import re

import device_protocol as wire

from phone_task_bridge import PhoneTaskError


def handle(manager, handler, post, parts, error):
    """Read only this endpoint's strict, bounded body on the original listener."""
    try:
        for name in ("Host", "Authorization", "Content-Length", "Content-Type"):
            if len(handler.headers.get_all(name, [])) != 1:
                raise PhoneTaskError("Exactly one local session and request framing header is required")
        if (handler.path != "/v1/phone-tasks" or handler.headers.get_all("Transfer-Encoding", [])
                or handler.headers.get_all("Origin", [])):
            raise PhoneTaskError("Use the exact authenticated local phone task endpoint", 403)
        content_type = handler.headers["Content-Type"].lower().replace(" ", "")
        if content_type not in {"application/json", "application/json;charset=utf-8"}:
            raise PhoneTaskError("UTF-8 JSON is required", 415)
        length = handler.headers["Content-Length"]
        if not re.fullmatch(r"[0-9]{1,5}", length) or not 1 <= int(length) <= 65536:
            raise PhoneTaskError("One bounded JSON request is required", 413)
        # Verify Host/source before reading body bytes or consulting device state.
        _local_surface(handler, post, parts)
        raw = handler.rfile.read(int(length))
        if len(raw) != int(length):
            raise PhoneTaskError("Incomplete local request body")
        try:
            params = wire.parse_json_object(raw)
        except wire.ProtocolError:
            raise PhoneTaskError("Invalid bounded UTF-8 JSON object") from None
        return route(manager, handler, post, parts, params, error)
    except PhoneTaskError as failure:
        raise error(str(failure), failure.status) from None


def _local_surface(handler, post, parts):
    if not ipaddress.ip_address(handler.client_address[0]).is_loopback:
        raise PhoneTaskError("The phone task adapter accepts loopback clients only", 403)
    port = handler.server.server_address[1]
    hosts = {"127.0.0.1:" + str(port), "localhost:" + str(port), "[::1]:" + str(port)}
    if handler.headers.get("Host") not in hosts:
        raise PhoneTaskError("Invalid local Host", 403)
    if handler.headers.get("Origin") is not None:
        raise PhoneTaskError("Browser calls must use the separately protected Task Desk", 403)
    if not post or parts != ["v1", "phone-tasks"]:
        raise PhoneTaskError("Use POST /v1/phone-tasks", 405)


def route(manager, handler, post, parts, params, error):
    # The ordinary Companion bearer has already been checked by its sole handler.
    # It is not a device grant: every routed call still needs current paired mTLS.
    try:
        _local_surface(handler, post, parts)
        if set(params) != {"action", "params"} or not isinstance(params["action"], str):
            raise PhoneTaskError("Use one explicit phone task action and its parameters")
        service = manager.device_companion
        if service is None or not hasattr(service, "phone_tasks"):
            raise PhoneTaskError("Enable the existing native paired device service first", 503)
        return service.phone_tasks.invoke(params["action"], params["params"])
    except PhoneTaskError as failure:
        raise error(str(failure), failure.status)
    except (ValueError, PermissionError):
        raise error("Current paired identity, native grant or exact task parameters were rejected", 403)
