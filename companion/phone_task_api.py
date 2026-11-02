"""Authenticated loopback adapter for the original Companion HTTP listener."""
import ipaddress

from phone_task_bridge import PhoneTaskError


def route(manager, handler, post, parts, params, error):
    # The ordinary Companion bearer has already been checked by its sole handler.
    # It is not a device grant: every routed call still needs current paired mTLS.
    try:
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
