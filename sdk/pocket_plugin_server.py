"""Explicitly started loopback development server for Pocket World's existing HTTP tool protocol.

No sockets open on import. Operation receipts live only until this development process exits.
Handlers are developer-owned Python functions, not downloaded plugin code loaded by the phone.
"""
from dataclasses import dataclass
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading
from urllib.parse import unquote

MAX_INPUT = 65536
MAX_OUTPUT = 262144


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


@dataclass(frozen=True)
class Tool:
    name: str
    handler: object


class LocalToolServer(ThreadingHTTPServer):
    """Start with serve_forever(); call shutdown()/server_close() when leaving the debug session."""
    daemon_threads = True
    block_on_close = False

    def __init__(self, address, tools, operation_limit=256):
        if address[0] != "127.0.0.1":
            raise ValueError("Development server binds only literal 127.0.0.1")
        if not 1 <= operation_limit <= 4096:
            raise ValueError("operation_limit must be in 1..4096")
        self.tools = {}
        for tool in tools:
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,63}", tool.name) or tool.name in self.tools or not callable(tool.handler):
                raise ValueError("Invalid or duplicate tool")
            self.tools[tool.name] = tool.handler
        if not 1 <= len(self.tools) <= 12:
            raise ValueError("Supply 1..12 tools")
        self.operation_limit = operation_limit
        self.operations = {}
        self.operations_lock = threading.Lock()
        super().__init__(address, _Handler)


class _Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_):
        pass  # No argument, token, path or response logging by default.

    def reply(self, code, value):
        body = _json(value)
        if len(body) > MAX_OUTPUT:
            code, body = 500, _json({"status": "unknown", "replayAllowed": False})
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        prefix = "/operations/"
        operation_id = unquote(self.path[len(prefix):]) if self.path.startswith(prefix) else ""
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", operation_id):
            return self.reply(404, {"status": "not_found"})
        with self.server.operations_lock:
            record = self.server.operations.get(operation_id)
            value = record[1] if record else {"status": "not_found", "operationId": operation_id, "replayAllowed": False}
        self.reply(200 if record else 404, value)

    def do_POST(self):
        if self.path != "/tools":
            return self.reply(404, {"status": "not_found"})
        if self.headers.get("Origin"):
            return self.reply(403, {"status": "browser_origin_rejected"})
        try:
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
                raise ValueError("Explicit body length required")
            length = int(lengths[0])
            if not 0 < length <= MAX_INPUT or not self.headers.get("Content-Type", "").lower().startswith("application/json"):
                raise ValueError("Invalid content type or size")
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("Incomplete body")
            request = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object,
                                 parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite number")))
            if not isinstance(request, dict) or set(request) != {"operationId", "tool", "arguments"}:
                raise ValueError("Invalid request fields")
            operation_id, name, arguments = request["operationId"], request["tool"], request["arguments"]
            if not isinstance(operation_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", operation_id):
                raise ValueError("Invalid operationId")
            if not isinstance(name, str) or name not in self.server.tools or not isinstance(arguments, dict):
                raise ValueError("Unknown tool or non-object arguments")
            digest = hashlib.sha256(_json(request)).hexdigest()
        except (ValueError, UnicodeError, OSError, RecursionError):
            return self.reply(400, {"status": "rejected", "replayAllowed": False})
        with self.server.operations_lock:
            previous = self.server.operations.get(operation_id)
            if previous:
                duplicate = (409, {"status": "operation_conflict", "replayAllowed": False}) if previous[0] != digest else (200, previous[1])
            elif len(self.server.operations) >= self.server.operation_limit:
                duplicate = (503, {"status": "receipt_capacity_reached", "replayAllowed": False})
            else:
                duplicate = None
                self.server.operations[operation_id] = (digest, {"status": "pending", "operationId": operation_id, "replayAllowed": False})
        if duplicate:
            return self.reply(*duplicate)
        try:
            output = self.server.tools[name](arguments)
            if not isinstance(output, dict):
                raise ValueError("Handlers return an object")
            receipt = {"status": "returned", "operationId": operation_id, "output": output, "untrusted": True, "replayAllowed": False}
            if len(_json(receipt)) > MAX_OUTPUT:
                raise ValueError("Output limit exceeded")
        except Exception:
            # The handler might already have changed something. Preserve the original operation; never rerun it.
            receipt = {"status": "unknown", "operationId": operation_id, "replayAllowed": False}
        with self.server.operations_lock:
            self.server.operations[operation_id] = (digest, receipt)
        self.reply(200, receipt)
