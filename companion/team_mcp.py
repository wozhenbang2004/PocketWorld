#!/usr/bin/env python3
"""Small stdio MCP endpoint for a single original team invocation."""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from team_harness import MAX_BYTES, TOOLS, TOOL_NAMES


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("The original team endpoint cannot redirect")


def endpoint_configuration():
    endpoint, token = os.environ.get("POCKET_TEAM_URL", ""), os.environ.get("POCKET_TEAM_TOKEN", "")
    parsed = urlsplit(endpoint)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path != "/tool"
            or parsed.port is None or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or len(token) < 32):
        raise ValueError("Original team endpoint is not configured")
    return endpoint, token


def call_host(name, arguments):
    endpoint, token = endpoint_configuration()
    raw = json.dumps({"name": name, "arguments": arguments}, ensure_ascii=False, allow_nan=False).encode()
    if len(raw) > MAX_BYTES:
        raise ValueError("Team request is too large")
    request = urllib.request.Request(endpoint, raw, {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    # This loopback-only transport must not send its one-job token to a proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    with opener.open(request, timeout=50) as response:
        body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise ValueError("Team answer is too large")
        return json.loads(body)


def serve(stream_in=sys.stdin.buffer, stream_out=sys.stdout.buffer):
    seen = {}
    initialized = False
    while True:
        line = stream_in.readline(MAX_BYTES + 1)
        if not line:
            return
        if len(line) > MAX_BYTES or not line.endswith(b"\n"):
            return
        identity = None
        try:
            request = json.loads(line)
            if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
                continue
            identity = request.get("id")
            if identity is None:
                continue
            if type(identity) not in {str, int} or len(str(identity)) > 200:
                continue
            fingerprint = json.dumps(request, sort_keys=True, ensure_ascii=False)
            key = (type(identity).__name__, str(identity))
            if key in seen:
                if seen[key][0] != fingerprint:
                    response = {"jsonrpc": "2.0", "id": identity, "error": {"code": -32600, "message": "Request identity was reused with different arguments"}}
                else:
                    response = seen[key][1]
            else:
                method, params = request.get("method"), request.get("params", {})
                if method == "initialize":
                    initialized = True
                    version = params.get("protocolVersion") if isinstance(params, dict) else None
                    result = {"protocolVersion": version if version in {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"} else "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "PocketWorld team", "version": "1.0.0"}}
                elif method == "ping":
                    result = {}
                elif not initialized:
                    raise ValueError("Initialize this team transport first")
                elif method == "tools/list":
                    result = {"tools": list(TOOLS)}
                elif method == "tools/call":
                    if not isinstance(params, dict) or set(params) - {"name", "arguments", "_meta"} or params.get("name") not in TOOL_NAMES:
                        raise ValueError("Unknown team tool")
                    try:
                        actual = call_host(params["name"], params.get("arguments", {}))
                        result = {"content": [{"type": "text", "text": json.dumps(actual, ensure_ascii=False)}], "isError": actual.get("ok") is False}
                    except (OSError, ValueError, urllib.error.URLError):
                        result = {"content": [{"type": "text", "text": "Original team host is unavailable; this call was not replayed."}], "isError": True}
                else:
                    response = {"jsonrpc": "2.0", "id": identity, "error": {"code": -32601, "message": "Unsupported method"}}
                    result = None
                if result is not None:
                    response = {"jsonrpc": "2.0", "id": identity, "result": result}
                if len(seen) >= 160:
                    return
                seen[key] = (fingerprint, response)
        except (ValueError, TypeError, UnicodeError, RecursionError):
            response = {"jsonrpc": "2.0", "id": identity, "error": {"code": -32602, "message": "Invalid team protocol request"}}
        stream_out.write((json.dumps(response, ensure_ascii=False, allow_nan=False) + "\n").encode())
        stream_out.flush()


def main():
    endpoint_configuration()
    serve()


if __name__ == "__main__":
    main()
