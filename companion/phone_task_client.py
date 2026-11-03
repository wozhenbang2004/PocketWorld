"""One request per owner action. No redirects, ambient proxies or write retries."""
from __future__ import annotations

import http.client
import json
import os
from pathlib import Path
import stat
import ssl
from urllib.parse import urlsplit


class ClientError(Exception):
    """A safe local error. Remote bodies and credential paths are never messages."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


MUTATIONS = frozenset({"run", "cancel", "acquire_control", "answer", "steer", "resume", "usb_open", "usb_close"})


def write_identity(action, params):
    if action == "cancel":
        return params.get("cancelOperationId")
    if action in {"acquire_control", "answer", "steer", "resume"}:
        return params.get("controlOperationId")
    return params.get("operationId")


def unknown_result(action, params):
    value = {"operationId": write_identity(action, params), "status": "unknown", "effectState": "unknown",
             "nextAction": "query_original_operation", "automaticRetry": False}
    if params.get("peerDeviceId"):
        value["peerDeviceId"] = params["peerDeviceId"]
    if action in {"cancel", "acquire_control", "answer", "steer", "resume"}:
        value["targetOperationId"] = params.get("operationId")
    return value


def strict_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON member")
            result[key] = value
        return result
    def invalid(_):
        raise ValueError("Non-finite JSON number")
    return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)


class Client:
    def __init__(self, endpoint, token_file, ca_file=None):
        try:
            address = urlsplit(endpoint)
            port = address.port
        except (TypeError, ValueError):
            raise ClientError("Use a valid explicit loopback HTTP(S) origin") from None
        if (address.scheme not in {"http", "https"} or address.username is not None or address.password is not None
                or address.hostname not in {"127.0.0.1", "::1", "localhost"} or address.query or address.fragment
                or address.path not in {"", "/"}):
            raise ClientError("Companion endpoint must be an explicit loopback HTTP(S) origin")
        if port is not None and not 1 <= port <= 65535:
            raise ClientError("Companion port must be between 1 and 65535")
        self.host, self.port, self.secure = address.hostname, port or (443 if address.scheme == "https" else 80), address.scheme == "https"
        try:
            self.tls_context = ssl.create_default_context(cafile=ca_file) if self.secure else None
        except (OSError, ValueError):
            raise ClientError("The selected Companion CA could not be loaded; verification remains enabled") from None
        path = Path(token_file).expanduser()
        if os.name == "nt":
            from platform_windows import WindowsBackend
            from platform_backend import PlatformError
            try:
                backend = WindowsBackend()
                if not path.is_absolute():
                    raise ClientError("Use an absolute private native token-file path")
                raw = backend.read_private_bytes(str(path.parent), path.name, backend.logon_context()["userSid"], maximum=4096)
            except (PlatformError, OSError):
                raise ClientError("Original Windows private-file ownership could not be verified") from None
        else:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
            try:
                descriptor = os.open(path, flags)
                with os.fdopen(descriptor, "rb") as stream:
                    info = os.fstat(stream.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_size > 4096 or info.st_uid != os.getuid() or info.st_mode & 0o077:
                        raise ClientError("Token file must be a small private file owned by the current user")
                    raw = stream.read(4097)
            except OSError:
                raise ClientError("The existing private Companion token file could not be opened") from None
        try:
            self._token = raw.decode("utf-8").strip()
        except (UnicodeError, AttributeError):
            raise ClientError("Invalid private Companion token file") from None
        if not 32 <= len(self._token) <= 2048 or any(ord(c) < 33 or ord(c) > 126 for c in self._token):
            raise ClientError("Invalid private Companion token file")

    def redact_diagnostic_text(self, text):
        """Remove the already loaded local secret without exposing it to the exporter."""
        return text.replace(self._token, "[redacted-known-secret]")

    def execute(self, action, params):
        if not isinstance(action, str) or not isinstance(params, dict):
            raise ClientError("Use one named phone action and object parameters")
        mutation = action in MUTATIONS
        if mutation and not isinstance(write_identity(action, params), str):
            raise ClientError("Assign the original operation ID before a possible write")
        timeout_ms = params.get("timeoutMs", 0)
        if type(timeout_ms) is not int or not 0 <= timeout_ms <= 45000:
            raise ClientError("Wait timeout must be an integer between 0 and 45000 milliseconds")
        try:
            body = json.dumps({"action": action, "params": params}, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        except (TypeError, ValueError, UnicodeError, RecursionError):
            raise ClientError("Use bounded finite JSON parameters") from None
        if len(body) > 65536:
            raise ClientError("Request exceeds the original control bound")
        timeout = min(60, max(30, timeout_ms / 1000 + 14))
        # http.client opens the selected host directly and never reads proxy env vars.
        connection = (http.client.HTTPSConnection(self.host, self.port, timeout=timeout, context=self.tls_context)
                      if self.secure else http.client.HTTPConnection(self.host, self.port, timeout=timeout))
        try:
            host = ("[" + self.host + "]" if ":" in self.host else self.host) + ":" + str(self.port)
            connection.request("POST", "/v1/phone-tasks", body, {"Host": host, "Authorization": "Bearer " + self._token, "Content-Type": "application/json", "Accept": "application/json"})
            response = connection.getresponse()
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("Reply exceeds the original control bound")
            if response.status >= 500 or 300 <= response.status < 400:
                if mutation:
                    return unknown_result(action, params)
                raise ClientError("Original read unavailable; no redirect or retry was attempted")
            if 400 <= response.status < 500:
                raise ClientError("Original Companion refused this action (HTTP " + str(response.status) + "). Review the phone connection and current scope.", response.status)
            value = strict_json(raw)
            if response.status != 200 or not isinstance(value, dict) or value.get("ok") is not True or not isinstance(value.get("result"), dict):
                raise ValueError("Original reply has no confirmed result")
            result = value["result"]
            if (result.get("peerDeviceId") is not None and result["peerDeviceId"] != params.get("peerDeviceId")
                    or mutation and result.get("operationId") != write_identity(action, params)
                    or action in {"status", "wait"} and result.get("operationId") != params.get("operationId")):
                raise ValueError("Original reply changed the selected phone or operation")
            return result
        except (OSError, http.client.HTTPException, ValueError, RecursionError) as failure:
            if mutation:
                return unknown_result(action, params)
            raise ClientError("Original read unavailable: " + type(failure).__name__)
        finally:
            connection.close()
