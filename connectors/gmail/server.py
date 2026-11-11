"""Owner-configured Gmail MCP adapter. No HTTP listener or model-driven login.

Google's client libraries own OAuth and Gmail requests; the MCP SDK owns stdio.
PocketWorld's original native tool approval owns consent to each concrete call.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import tempfile
import threading
import time
from email.headerregistry import Address
from email.message import EmailMessage
from email.policy import SMTP
from urllib.parse import urlsplit

READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send"
MAX_BODY = 16000
TOKEN_ENDPOINTS = {"https://oauth2.googleapis.com/token", "https://accounts.google.com/o/oauth2/token"}


class Rejected(Exception):
    """Safe, bounded messages that may be returned to the calling model."""


def text(value, maximum, label, empty=False):
    if not isinstance(value, str) or len(value) > maximum or (not empty and not value.strip()):
        raise Rejected(f"Invalid {label}.")
    return value


def address(value):
    value = text(value, 254, "email address")
    if any(c in value for c in "\r\n\x00") or value != value.strip():
        raise Rejected("Use a plain email address without display names or line breaks.")
    try:
        parsed = Address(addr_spec=value)
        if not parsed.username or not parsed.domain or parsed.addr_spec != value:
            raise ValueError()
        value.encode("ascii")
    except (ValueError, UnicodeError):
        raise Rejected("Use an ASCII mailbox address, not a display name or address list.") from None
    return value


def account(value):
    return address(value).casefold()


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Rejected("The credential/state file must be an owner-only regular file.")


def state_directory(value):
    path = Path(value).expanduser().absolute()
    # Every invocation uses one stable directory, never the task's frozen source copy.
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Rejected("State directory must be owned by this user with mode 0700 and not a symlink.")
    return path


@contextlib.contextmanager
def exclusive(directory):
    fd = os.open(directory / "account.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        private_file(directory / "account.lock")
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        os.close(fd)


def save_credentials(directory, expected, credentials):
    target = directory / "credentials.json"
    if target.exists():
        private_file(target)
    body = json.dumps({"account": expected, "credentials": json.loads(credentials.to_json())})
    fd, name = tempfile.mkstemp(prefix=".credentials-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, target)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def service_for_token(token):
    from google.oauth2.credentials import Credentials
    from google.auth.transport.requests import AuthorizedSession
    from googleapiclient.discovery import build
    import httplib2
    from requests.adapters import HTTPAdapter

    class NoReplayHttp:
        def __init__(self):
            # httplib2 can replay a POST after BadStatusLine even with RETRIES=1.
            # Use Google's requests transport with explicit zero retries instead.
            self.session = AuthorizedSession(Credentials(token=token), max_refresh_attempts=0)
            self.session.mount("https://", HTTPAdapter(max_retries=0))
            self.session.trust_env = False

        def request(self, uri, method="GET", body=None, headers=None, **_):
            url = urlsplit(uri)
            if url.scheme != "https" or url.hostname not in {"gmail.googleapis.com", "www.googleapis.com"} or url.username or url.password or url.port not in {None, 443}:
                raise Rejected("Gmail request destination is not allowed.")
            with self.session.request(method, uri, data=body, headers=headers, timeout=20, allow_redirects=False, stream=True) as response:
                chunks, size = [], 0
                for chunk in response.iter_content(65536):
                    size += len(chunk)
                    if size > 40 * 1024 * 1024:
                        raise Rejected("Gmail response exceeds the connector limit.")
                    chunks.append(chunk)
                result = httplib2.Response(dict(response.headers))
                result.status = response.status_code
                result.reason = response.reason
                return result, b"".join(chunks)

        def close(self):
            self.session.close()

    return build("gmail", "v1", http=NoReplayHttp(), cache_discovery=False, static_discovery=True)


class Mailbox:
    def __init__(self, directory, expected, allow_send):
        self.directory, self.expected, self.allow_send = directory, account(expected), allow_send

    @contextlib.contextmanager
    def service(self, sending=False):
        from google.oauth2.credentials import Credentials
        from google.auth.transport.requests import Request
        path = self.directory / "credentials.json"
        if not path.exists():
            raise Rejected("Owner login required. Run the separate authorize command; no login was started.")
        private_file(path)
        stored = json.loads(path.read_text(encoding="utf-8"))
        if stored.get("account") != self.expected:
            raise Rejected("The configured account changed. Reconnect and review the original installation.")
        if stored.get("credentials", {}).get("token_uri") not in TOKEN_ENDPOINTS:
            raise Rejected("Stored authorization is not bound to Google's token endpoint.")
        credentials = Credentials.from_authorized_user_info(stored["credentials"])
        required = [READ_SCOPE] + ([SEND_SCOPE] if sending else [])
        if not credentials.has_scopes(required):
            raise Rejected("Owner authorization lacks the required Gmail scopes. Reauthorize explicitly.")
        if not credentials.valid:
            try:
                credentials.refresh(Request())
            except Exception:
                raise Rejected("Authorization expired or was revoked. Owner reauthorization is required; no mail action was sent.") from None
            save_credentials(self.directory, self.expected, credentials)
        service = service_for_token(credentials.token)
        try:
            profile = service.users().getProfile(userId="me").execute(num_retries=0)
            if account(profile.get("emailAddress")) != self.expected:
                raise Rejected("Authenticated Gmail account does not match the owner-selected account.")
            yield service
        finally:
            service.close()

    @contextlib.contextmanager
    def ledger(self):
        path = self.directory / "outbox.sqlite"
        fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        private_file(path)
        db = sqlite3.connect(path)
        try:
            db.execute("PRAGMA synchronous=FULL")
            db.execute("CREATE TABLE IF NOT EXISTS sends (operation TEXT PRIMARY KEY, digest TEXT UNIQUE NOT NULL, account TEXT NOT NULL, message_header TEXT UNIQUE NOT NULL, state TEXT NOT NULL, message_id TEXT, thread_id TEXT, created INTEGER NOT NULL)")
            db.commit()
            yield db
        finally:
            db.close()

    def call(self, name, args, cancelled):
        # Serializes credential refresh and send deduplication across independent MCP processes.
        with exclusive(self.directory):
            if cancelled.is_set():
                raise Rejected("Original MCP request was cancelled; no new action was dispatched.")
            if name == "gmail_send_status":
                return self.send_status(args)
            if name == "gmail_send":
                return self.send(args, cancelled)
            with self.service() as service:
                if name == "gmail_account":
                    return {"account": self.expected, "sendEnabled": self.allow_send, "authorized": True}
                if name == "gmail_search":
                    query = text(args.get("query"), 1000, "query")
                    size = args.get("limit", 20)
                    if type(size) is not int or not 1 <= size <= 20:
                        raise Rejected("limit must be from 1 to 20.")
                    params = {"userId": "me", "q": query, "maxResults": size, "includeSpamTrash": False}
                    if args.get("page_token"):
                        params["pageToken"] = text(args["page_token"], 2048, "page token")
                    result = service.users().messages().list(**params).execute(num_retries=0)
                    return {"account": self.expected, "messages": result.get("messages", []), "nextPageToken": result.get("nextPageToken"), "contentRead": False}
                if name == "gmail_read":
                    message_id = text(args.get("message_id"), 128, "message id")
                    if not re.fullmatch(r"[A-Za-z0-9_-]+", message_id):
                        raise Rejected("Invalid Gmail message id.")
                    result = service.users().messages().get(userId="me", id=message_id, format="full").execute(num_retries=0)
                    return self.read_view(result)
            raise Rejected("Unknown tool.")

    def read_view(self, result):
        payload = result.get("payload", {})
        headers = {h["name"].lower(): h.get("value", "")[:2000] for h in payload.get("headers", []) if h.get("name", "").lower() in {"from", "to", "cc", "subject", "date", "message-id"}}
        pending, parts, attachments = [payload], [], []
        remaining, truncated, visited = MAX_BODY, False, 0
        while pending and visited < 100:
            part = pending.pop(0)
            visited += 1
            pending.extend(part.get("parts", []))
            body = part.get("body", {})
            if part.get("filename") or body.get("attachmentId"):
                attachments.append({"filename": part.get("filename", "")[:240], "mimeType": part.get("mimeType"), "size": body.get("size"), "downloaded": False})
                continue
            encoded = body.get("data", "")
            if part.get("mimeType") in {"text/plain", "text/html"} and encoded:
                raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
                value = raw.decode("utf-8", errors="replace")
                used = min(remaining, len(value))
                parts.append({"mimeType": part["mimeType"], "text": value[:used]})
                remaining -= used
                truncated |= used < len(value)
        return {"account": self.expected, "id": result.get("id"), "threadId": result.get("threadId"), "headers": headers, "parts": parts, "attachments": attachments, "truncated": truncated or bool(pending), "untrustedContent": True, "htmlRendered": False, "remoteResourcesLoaded": False}

    def send(self, args, cancelled):
        if not self.allow_send:
            raise Rejected("Sending is disabled. The owner must explicitly reconnect with --enable-send.")
        operation = text(args.get("operation_id"), 80, "operation id")
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,80}", operation):
            raise Rejected("Use a stable 16–80 character operation id for this approved email.")
        selected_account = account(args.get("account"))
        if selected_account != self.expected:
            raise Rejected("The sender must match the owner-selected account.")
        recipients = {}
        for field in ("to", "cc", "bcc"):
            values = args.get(field, [])
            if not isinstance(values, list) or len(values) > 10 or (field == "to" and not values):
                raise Rejected("Provide 1–10 To recipients and at most 10 Cc/Bcc recipients each.")
            recipients[field] = [address(value) for value in values]
        subject = text(args.get("subject"), 500, "subject")
        body = text(args.get("body"), MAX_BODY, "body")
        if any(c in subject for c in "\r\n\x00") or "\x00" in body:
            raise Rejected("The subject cannot contain line breaks; the message cannot contain NUL.")
        exact = {"account": self.expected, **recipients, "subject": subject, "body": body}
        digest = hashlib.sha256(json.dumps(exact, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with self.ledger() as db:
            existing = db.execute("SELECT operation, digest, state, message_id, thread_id FROM sends WHERE operation=? OR digest=?", (operation, digest)).fetchone()
            if existing:
                if existing[1] != digest:
                    raise Rejected("This operation id belongs to a different approved message.")
                return {"status": existing[2], "operation_id": existing[0], "messageId": existing[3], "threadId": existing[4], "dispatchedNow": False, "replayAllowed": False}
            if db.execute("SELECT COUNT(*) FROM sends").fetchone()[0] >= 5000:
                raise Rejected("The send ledger requires owner maintenance; no message was sent.")
            # All validation and account checks precede the durable dispatch fence.
            with self.service(sending=True) as service:
                header = f"<pw-{hashlib.sha256((self.expected + ':' + operation).encode()).hexdigest()}@pocketworld.invalid>"
                message = EmailMessage(policy=SMTP)
                message["From"] = self.expected
                for field, values in recipients.items():
                    if values:
                        message[field.title()] = ", ".join(values)
                message["Subject"] = subject
                message["Message-ID"] = header
                message.set_content(body)
                raw = base64.urlsafe_b64encode(message.as_bytes()).decode("ascii")
                if cancelled.is_set():
                    raise Rejected("Original MCP request was cancelled before send admission.")
                db.execute("INSERT INTO sends VALUES (?, ?, ?, ?, 'unknown', NULL, NULL, ?)", (operation, digest, self.expected, header, int(time.time())))
                db.commit()
                try:
                    if cancelled.is_set():
                        raise Rejected("Original request was cancelled; preserve the durable fence.")
                    result = service.users().messages().send(userId="me", body={"raw": raw}).execute(num_retries=0)
                    message_id, thread_id = result.get("id"), result.get("threadId")
                    if not isinstance(message_id, str) or not message_id:
                        raise ValueError("Missing send receipt")
                    db.execute("UPDATE sends SET state='sent', message_id=?, thread_id=? WHERE operation=?", (message_id, thread_id, operation))
                    db.commit()
                    return {"status": "sent", "account": self.expected, "operation_id": operation, "messageId": message_id, "threadId": thread_id, "delivered": "not_verified", "replayAllowed": False}
                except Exception:
                    # Any outcome after the fence, including save failure, stays non-replayable.
                    return {"status": "unknown", "account": self.expected, "operation_id": operation, "message": "No definitive send receipt. Use gmail_send_status or inspect Sent in Gmail. Do not send again.", "replayAllowed": False}

    def send_status(self, args):
        operation = text(args.get("operation_id"), 80, "operation id")
        with self.ledger() as db:
            row = db.execute("SELECT account, message_header, state, message_id, thread_id FROM sends WHERE operation=?", (operation,)).fetchone()
            if not row:
                return {"status": "not_recorded", "operation_id": operation, "replayAllowed": False}
            if row[0] != self.expected:
                raise Rejected("This send record belongs to another account.")
            status, message_id, thread_id = row[2:]
            if status == "unknown":
                with self.service() as service:
                    found = service.users().messages().list(userId="me", q="in:sent rfc822msgid:" + row[1], maxResults=2).execute(num_retries=0)
                    candidates = found.get("messages", [])
                    if len(candidates) == 1:
                        candidate = service.users().messages().get(userId="me", id=candidates[0]["id"], format="metadata", metadataHeaders=["Message-ID"]).execute(num_retries=0)
                        headers = candidate.get("payload", {}).get("headers", [])
                        if "SENT" in candidate.get("labelIds", []) and any(h.get("name", "").lower() == "message-id" and h.get("value") == row[1] for h in headers):
                            status, message_id, thread_id = "sent", candidate["id"], candidate.get("threadId")
                            db.execute("UPDATE sends SET state='sent', message_id=?, thread_id=? WHERE operation=?", (message_id, thread_id, operation))
                            db.commit()
            return {"status": status, "operation_id": operation, "messageId": message_id, "threadId": thread_id, "delivered": "not_verified", "replayAllowed": False}


def authorize(args, directory):
    from google_auth_oauthlib.flow import InstalledAppFlow
    expected = account(args.account)
    client = Path(args.client).expanduser()
    private_file(client)
    with exclusive(directory):
        target = directory / "credentials.json"
        if target.exists():
            private_file(target)
            if not args.replace or json.loads(target.read_text())["account"] != expected:
                raise Rejected("Use --replace only to reauthorize this same account; choose a separate state directory for another account.")
        if not sys.stdin.isatty():
            raise Rejected("Owner authorization requires an interactive terminal, never an MCP tool call.")
        scopes = [READ_SCOPE] + ([SEND_SCOPE] if args.enable_send else [])
        print(f"Authorize {expected} for Gmail reading" + (" and sending" if args.enable_send else " only") + ".", file=sys.stderr)
        print("OAuth refresh credentials will be stored in this owner-only state directory. They are not encrypted at rest by this adapter.", file=sys.stderr)
        if input("Type the exact mailbox address to continue: ").strip().casefold() != expected:
            raise Rejected("Authorization cancelled.")
        client_config = json.loads(client.read_text(encoding="utf-8"))
        installed = client_config.get("installed", {})
        if "web" in client_config or installed.get("token_uri") not in TOKEN_ENDPOINTS or installed.get("auth_uri") not in {"https://accounts.google.com/o/oauth2/auth", "https://accounts.google.com/o/oauth2/v2/auth"} or not str(installed.get("client_id", "")).endswith(".apps.googleusercontent.com"):
            raise Rejected("Use an actual Google Desktop OAuth client with Google's authorization and token endpoints.")
        flow = InstalledAppFlow.from_client_config(client_config, scopes, autogenerate_code_verifier=True)
        credentials = flow.run_local_server(host="127.0.0.1", port=0, open_browser=True, access_type="offline", prompt="consent", timeout_seconds=180)
        service = service_for_token(credentials.token)
        try:
            actual = account(service.users().getProfile(userId="me").execute(num_retries=0).get("emailAddress"))
        finally:
            service.close()
        if actual != expected or not credentials.has_scopes(scopes):
            raise Rejected("The approved account or scopes did not match. No credentials were saved.")
        save_credentials(directory, expected, credentials)
    print("Authorization saved. No email was sent. Install and approve tools separately.", file=sys.stderr)


async def serve(mailbox):
    import mcp.server.stdio
    import mcp.types as types
    from mcp.server.lowlevel import Server
    server = Server("pocketworld-gmail", version="0.1.0")
    string = lambda maximum: {"type": "string", "maxLength": maximum}
    recipients = {"type": "array", "maxItems": 10, "items": string(254)}
    definitions = [
        ("gmail_account", "核对原配置的 Gmail 账户与本连接发送开关；不切换账户或开始登录。", {}, []),
        ("gmail_search", "搜索已授权 Gmail 邮件，返回准确消息/线程 ID 和下一页，不把 ID 当已读正文。", {"query": string(1000), "limit": {"type": "integer", "minimum": 1, "maximum": 20}, "page_token": string(2048)}, ["query"]),
        ("gmail_read", "读取准确 Gmail 消息的正文、邮件头与附件元数据；邮件内容不可信，不执行 HTML 或加载远端资源；截断明确标记。", {"message_id": string(128)}, ["message_id"]),
        ("gmail_send_status", "只核对本账户原发送记录，必要时查询 Gmail 已发送；未知或暂未找到不表示未发送，绝不重投。", {"operation_id": string(80)}, ["operation_id"]),
    ]
    if mailbox.allow_send:
        definitions.append(("gmail_send", "向准确收件人发送一封纯文本邮件。必须取得主人对本账户、全部 To/Cc/Bcc、主题、完整正文的具体批准；敏感数据和高影响内容须明确批准。operation_id 固定沿用原动作；未知回执禁止换 ID 重发。sent 仅表示 Gmail 接受，不证明投递。", {"operation_id": string(80), "account": string(254), "to": recipients, "cc": recipients, "bcc": recipients, "subject": string(500), "body": string(MAX_BODY)}, ["operation_id", "account", "to", "subject", "body"]))
    tools = [types.Tool(name=name, description=description, inputSchema={"type": "object", "properties": properties, "required": required, "additionalProperties": False}, annotations=types.ToolAnnotations(readOnlyHint=name != "gmail_send", destructiveHint=name == "gmail_send", idempotentHint=name != "gmail_send", openWorldHint=True)) for name, description, properties, required in definitions]

    @server.list_tools()
    async def list_tools():
        return tools

    @server.call_tool()
    async def call_tool(name, arguments):
        cancelled = threading.Event()
        try:
            if name not in {tool.name for tool in tools}:
                raise Rejected("Tool is not enabled on this connection.")
            value = await asyncio.to_thread(mailbox.call, name, arguments, cancelled)
            return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(value, ensure_ascii=False))], isError=value.get("status") == "unknown")
        except asyncio.CancelledError:
            # Cancelling the await does not stop a worker thread. Prevent a queued
            # worker from sending later; an already dispatched request remains unknown.
            cancelled.set()
            raise
        except Rejected as failure:
            return types.CallToolResult(content=[types.TextContent(type="text", text=str(failure))], isError=True)
        except Exception:
            # Never return request URLs, OAuth errors, token material or raw tracebacks.
            return types.CallToolResult(content=[types.TextContent(type="text", text="Gmail request unavailable. Check owner authorization and original send status; do not replay a send.")], isError=True)

    async with mcp.server.stdio.stdio_server() as streams:
        await server.run(streams[0], streams[1], server.create_initialization_options())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["authorize", "serve"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--state-dir", required=True, help="Stable owner-only storage outside task/repository copies")
    parser.add_argument("--enable-send", action="store_true", help="Explicitly authorize/expose sending; read-only by default")
    parser.add_argument("--client", help="Owner-provided Desktop OAuth client JSON, authorize only")
    parser.add_argument("--replace", action="store_true", help="Explicit same-account reauthorization, authorize only")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        directory = state_directory(args.state_dir)
        if args.command == "authorize":
            if not args.client:
                raise Rejected("Provide the owner's Desktop OAuth client with --client.")
            authorize(args, directory)
        else:
            if args.client or args.replace:
                raise Rejected("The MCP server cannot start or replace an authorization.")
            asyncio.run(serve(Mailbox(directory, args.account, args.enable_send)))
    except Rejected as failure:
        print(str(failure), file=sys.stderr)
        return 2
    except Exception:
        print("Connector unavailable. Inspect the local setup without exposing credentials; no automatic retry was started.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
