"""Ephemeral one-phone localhost Task Desk and authenticated MCP HTTP adapter."""
from __future__ import annotations
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import secrets
import threading
import re

from phone_task_mcp import VERSION, rpc


class TaskDesk(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, client, fixed_peer, port=0):
        if not isinstance(fixed_peer, str) or not re.fullmatch(r"pw1_[a-f0-9]{64}", fixed_peer):
            raise ValueError("Select one exact already paired phone identity")
        self.client, self.fixed_peer = client, fixed_peer
        self.session_token = secrets.token_urlsafe(32)
        super().__init__(("127.0.0.1", port), Handler)
        self.origin = "http://127.0.0.1:" + str(self.server_address[1])
        self.worker = None

    def start(self):
        self.worker = threading.Thread(target=self.serve_forever, name="phone-task-desk", daemon=True)
        self.worker.start()
        return self.origin + "/#" + self.session_token

    def close(self):
        if self.worker:
            self.shutdown()
            self.worker.join(timeout=2)
        self.server_close()  # Closing a desktop view does not cancel a phone task.


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def _send(self, code, body=None, kind="application/json"):
        raw = b"" if body is None else body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def _guard(self, authenticated=False):
        if not ipaddress.ip_address(self.client_address[0]).is_loopback or self.headers.get("Host") != self.server.origin.removeprefix("http://"):
            return False
        origin = self.headers.get("Origin")
        if origin is not None and origin != self.server.origin:
            return False
        if authenticated:
            token = self.headers.get("Authorization", "")
            if not hmac.compare_digest(token.encode(), ("Bearer " + self.server.session_token).encode()):
                return False
        return True

    def do_GET(self):
        if not self._guard():
            self._send(403); return
        if self.path == "/mcp":
            self._send(405); return  # No standalone SSE stream.
        assets = {"/": ("phone_task_desk.html", "text/html; charset=utf-8"), "/desk.js": ("phone_task_desk.js", "text/javascript; charset=utf-8"), "/desk.css": ("phone_task_desk.css", "text/css; charset=utf-8")}
        asset = assets.get(self.path)
        if asset is None:
            self._send(404); return
        self._send(200, Path(__file__).with_name(asset[0]).read_bytes(), asset[1])

    def do_POST(self):
        if not self._guard(True):
            self._send(403); return
        if self.path not in {"/api", "/mcp"} or self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            self._send(415); return
        length = self.headers.get("Content-Length", "")
        if not length.isdigit() or not 0 < int(length) <= 65536 or self.headers.get("Transfer-Encoding") is not None:
            self._send(413); return
        self.connection.settimeout(60)
        try:
            raw = self.rfile.read(int(length))
            if len(raw) != int(length):
                raise ValueError()
            message = json.loads(raw)
            if self.path == "/mcp":
                if "application/json" not in self.headers.get("Accept", "") or "text/event-stream" not in self.headers.get("Accept", ""):
                    self._send(406); return
                if isinstance(message, dict) and message.get("method") != "initialize" and self.headers.get("MCP-Protocol-Version") != VERSION:
                    self._send(400); return
                response = rpc(self.server.client, message, fixed_peer=self.server.fixed_peer)
                self._send(202 if response is None else 200, response)
            else:
                if not isinstance(message, dict) or set(message) != {"action", "params"} or not isinstance(message["params"], dict):
                    raise ValueError()
                if message["action"] == "selection" and message["params"] == {}:
                    response = {"peerDeviceId": self.server.fixed_peer}
                else:
                    if message["params"].get("peerDeviceId") != self.server.fixed_peer:
                        self._send(403); return
                    response = self.server.client.execute(message["action"], message["params"])
                self._send(200, {"ok": True, "result": response})
        except (ValueError, UnicodeError):
            self._send(400, {"error": "Invalid bounded JSON action"})
        except Exception:
            self._send(503, {"error": "Original phone action unavailable; query its original operation. No automatic retry."})
