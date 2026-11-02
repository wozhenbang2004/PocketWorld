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
    pass


class Client:
    def __init__(self, endpoint, token_file, ca_file=None):
        address = urlsplit(endpoint)
        if (address.scheme not in {"http", "https"} or address.username is not None or address.password is not None
                or address.hostname not in {"127.0.0.1", "::1", "localhost"} or address.query or address.fragment
                or address.path not in {"", "/"}):
            raise ClientError("Companion endpoint must be an explicit loopback HTTP(S) origin")
        self.host, self.port, self.secure = address.hostname, address.port or (443 if address.scheme == "https" else 80), address.scheme == "https"
        self.tls_context = ssl.create_default_context(cafile=ca_file) if self.secure else None
        path = Path(token_file).expanduser()
        if os.name == "nt":
            from platform_windows import WindowsBackend
            from platform_backend import PlatformError
            try:
                backend = WindowsBackend()
                if not path.is_absolute():
                    raise ClientError("Use an absolute private native token-file path")
                raw = backend.read_private_bytes(str(path.parent), path.name, backend.logon_context()["userSid"], maximum=4096)
            except PlatformError:
                raise ClientError("Original Windows private-file ownership could not be verified")
        else:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(path, flags)
            with os.fdopen(descriptor, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > 4096 or info.st_uid != os.getuid() or info.st_mode & 0o077:
                    raise ClientError("Token file must be a small private file owned by the current user")
                raw = stream.read(4097)
        self._token = raw.decode("utf-8").strip()
        if not 32 <= len(self._token) <= 2048 or any(ord(c) < 33 or ord(c) > 126 for c in self._token):
            raise ClientError("Invalid private Companion token file")

    def execute(self, action, params):
        mutation = action in {"run", "cancel", "answer", "steer", "usb_open", "usb_close"}
        operation_id = params.get("cancelOperationId") if action == "cancel" else params.get("operationId")
        body = json.dumps({"action": action, "params": params}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(body) > 65536:
            raise ClientError("Request exceeds the original control bound")
        timeout = min(58, max(30, params.get("timeoutMs", 0) / 1000 + 14))
        # http.client opens the selected host directly and never reads proxy env vars.
        connection = (http.client.HTTPSConnection(self.host, self.port, timeout=timeout, context=self.tls_context)
                      if self.secure else http.client.HTTPConnection(self.host, self.port, timeout=timeout))
        try:
            host = ("[" + self.host + "]" if ":" in self.host else self.host) + ":" + str(self.port)
            connection.request("POST", "/v1/phone-tasks", body, {"Host": host, "Authorization": "Bearer " + self._token, "Content-Type": "application/json", "Accept": "application/json"})
            response = connection.getresponse()
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ClientError("Reply exceeds the original control bound")
            if response.status >= 500 or 300 <= response.status < 400:
                if mutation:
                    return {"operationId": operation_id, "status": "unknown", "effectState": "unknown", "nextAction": "query_original_operation", "automaticRetry": False}
                raise ClientError("Original read unavailable; no redirect or retry was attempted")
            value = json.loads(raw)
            if response.status != 200 or not isinstance(value, dict) or value.get("ok") is not True or not isinstance(value.get("result"), dict):
                raise ClientError("Original Companion refused this action (HTTP " + str(response.status) + ")")
            return value["result"]
        except (OSError, http.client.HTTPException, ValueError) as failure:
            if mutation:
                return {"operationId": operation_id, "status": "unknown", "effectState": "unknown", "nextAction": "query_original_operation", "automaticRetry": False}
            raise ClientError("Original read unavailable: " + type(failure).__name__)
        finally:
            connection.close()
