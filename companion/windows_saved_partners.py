"""Explicit cold, local-only view of this signed Windows install's paired partners."""
from contextlib import ExitStack, closing
import hashlib
import ntpath
from pathlib import Path
import sqlite3
import sys
import time

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
import device_identity as identity
import device_protocol as protocol
from device_pairing import _verify_record_certificate
from platform_backend import PlatformError
from platform_windows import WindowsHandleReader
from windows_first_workspace import require_owner
from windows_installation import _object, same_path
from windows_local_read_handle import attributes, open_fixed


LEDGER = "operations.sqlite3"
MAX_DB = 256 * 1024 * 1024
MAX_SHOWN = 8
MAX_CERTIFICATE = 16384
MAX_STORED_RECEIPT = 2 * protocol.MAX_PAIRING_BYTES
MAX_COMMIT = 4096
TRUST_COLUMNS = {
    "device_peers": frozenset("device_id certificate_der fingerprint instance_id authorization_epoch revoked".split()),
    "device_pairing_invites": frozenset("invite_id inviter_device_id instance_id state peer_device_id transcript_digest expires_at receipt_json".split()),
}
COMMIT_FIELDS = frozenset("commitId committedAt inviteId transcriptDigest grantDigest grantId authorizationEpoch inviterDeviceId inviterInstanceId inviterFingerprint joinerDeviceId joinerInstanceId joinerFingerprint".split())


def _unavailable():
    raise PlatformError("Original local pairing ledger could not be verified")


def _check_config(ctx, settings):
    # Unlike launch_config, this never reads token or key bytes.
    raw = ctx._raw(ctx.state, "companion.json", 262144)
    config = _object(raw)
    devices = config.get("devices")
    if (hashlib.sha256(raw).hexdigest() != settings or not isinstance(devices, dict)
            or not same_path(devices.get("identityDir"), ctx.identity_dir) or not same_path(config.get("stateDir"), ctx.jobs)):
        _unavailable()


def _lookup_only(action, name, column, schema, source):
    if source is None:
        if action == sqlite3.SQLITE_SELECT:
            return sqlite3.SQLITE_OK
        if action == sqlite3.SQLITE_READ and schema == "main" and column in TRUST_COLUMNS.get(name, ()):
            return sqlite3.SQLITE_OK
        if action == sqlite3.SQLITE_FUNCTION and column in {"typeof", "length", "json_valid", "json_extract"}:
            return sqlite3.SQLITE_OK
    return sqlite3.SQLITE_DENY


def _checked_partner(db, local, peer):
    peer_id, peer_cert, fingerprint, instance, epoch, revoked = peer
    cert, local_id, local_fingerprint, local_instance = local
    try:
        if (not isinstance(peer_cert, bytes) or identity.device_id(peer_cert) != protocol.device_id(peer_id)
                or hashlib.sha256(peer_cert).hexdigest() != fingerprint or peer_id == local_id
                or type(revoked) is not int or revoked not in (0, 1)):
            return None
        protocol.identifier(instance, "peer instance")
        protocol.integer(epoch, "current local epoch", 2 if revoked else 1)
        # Read only the signed commit object, never grant/event bodies or the
        # whole wrapping receipt into Python. Eligibility comes from this install.
        entries = db.execute("""
            SELECT invite_id, transcript_digest, expires_at,
              CASE WHEN typeof(receipt_json)='text' AND length(CAST(receipt_json AS BLOB))<=?
                   THEN CASE WHEN json_valid(receipt_json) THEN json_extract(receipt_json,'$.commitReceipt') END
              END
            FROM main.device_pairing_invites
            WHERE inviter_device_id=? AND instance_id=? AND peer_device_id=? AND state='consumed'
            LIMIT 2
            """, (MAX_STORED_RECEIPT, local_id, local_instance, peer_id)).fetchall()
        if len(entries) != 1:
            return None  # Includes reverse-origin and ambiguous records.
        invite, digest, expires, raw = entries[0]
        if not isinstance(raw, str):
            return None
        raw = raw.encode("utf-8")
        if len(raw) > MAX_COMMIT:
            return None
        protocol.identifier(invite, "invite")
        protocol.integer(expires, "original expiry", 1)
        record = protocol.parse_json_object(raw)
        if not isinstance(record.get("signature"), str):
            return None  # Malformed local proof, not evidence of a missing partner.
        proof = _verify_record_certificate(cert, record, "PairingCommitReceipt")
        protocol._keys(proof, COMMIT_FIELDS)
        protocol.identifier(proof["commitId"], "commit")
        protocol.integer(proof["authorizationEpoch"], "original epoch", 1, 1)
        protocol.integer(proof["committedAt"], "original commit time", 1, expires - 1)
        if (not isinstance(digest, str) or not protocol.DIGEST.fullmatch(digest)
                or proof["transcriptDigest"] != digest or proof["inviteId"] != invite
                or (proof["inviterDeviceId"], proof["inviterFingerprint"], proof["inviterInstanceId"]) != (local_id, local_fingerprint, local_instance)
                or (proof["joinerDeviceId"], proof["joinerFingerprint"], proof["joinerInstanceId"]) != (peer_id, fingerprint, instance)):
            return None
        # Epoch one is the saved original commit, NOT the live grant epoch.
        # Later native revocation still identifies this same original tuple.
        return peer_id, fingerprint, bool(revoked)
    except (InvalidSignature, UnsupportedAlgorithm, PermissionError, ValueError):
        return None


def _read_only(path, local):
    uri = Path(path).as_uri() + "?mode=ro&immutable=1"
    with closing(sqlite3.connect(uri, uri=True, isolation_level=None, timeout=0)) as db:
        db.setlimit(sqlite3.SQLITE_LIMIT_ATTACHED, 0)
        db.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, 256 * 1024)
        db.execute("PRAGMA trusted_schema=OFF")
        db.execute("PRAGMA query_only=ON")
        db.execute("PRAGMA temp_store=MEMORY")
        deadline, budget = time.monotonic() + 1.0, 180

        def stop():
            nonlocal budget
            budget -= 1
            return budget <= 0 or time.monotonic() >= deadline

        db.set_progress_handler(stop, 1000)
        # Real native tables only, not views, virtual tables or a substitute schema.
        for table in TRUST_COLUMNS:
            row = db.execute(f"PRAGMA main.table_list('{table}')").fetchone()
            if row is None or row[:3] != ("main", table, "table"):
                _unavailable()
        db.set_authorizer(_lookup_only)
        peers = db.execute("""
            SELECT device_id,
              CASE WHEN typeof(certificate_der)='blob' AND length(certificate_der) BETWEEN 64 AND ? THEN certificate_der END,
              fingerprint, instance_id, authorization_epoch, revoked
            FROM main.device_peers ORDER BY device_id LIMIT ?
            """, (MAX_CERTIFICATE, MAX_SHOWN + 1)).fetchall()
        rows = tuple(value for peer in peers[:MAX_SHOWN] if (value := _checked_partner(db, local, peer)) is not None)
        return rows, len(peers) != len(rows)


def _check_no_sidecars(api, path):
    if any(attributes(api, path + suffix, absent=True) is not None for suffix in ("-journal", "-wal", "-shm")):
        _unavailable()  # Never read an unrecovered rollback or WAL state as cold.


def _capture(ctx, settings, owner):
    from desktop_worker import original_state_idle

    api = ctx.backend.api
    # Stock producer mutex plus physical share-read-only handles (also exclude
    # an actual write handle not covered by this logon session's mutex).
    with original_state_idle(ctx.jobs), ExitStack() as held:
        if require_owner(ctx) != owner:
            _unavailable()
        _check_config(ctx, settings)
        _, state, _ = held.enter_context(ctx.backend.directory(ctx.state))
        api.private_access(state, ctx.sid, require_protected=True)
        for directory in (ctx.jobs, ctx.identity_dir):
            handle, _, _ = open_fixed(api, directory, True, held, owner_acl=True)
            api.private_access(handle, ctx.sid, require_protected=directory == ctx.identity_dir)

        def reader(path, maximum):
            file, _, _ = open_fixed(api, path, False, held, body=True)
            api.private_access(file, ctx.sid)
            return WindowsHandleReader(api, file, maximum)

        cert_file = reader(ntpath.join(ctx.identity_dir, "certificate.der"), MAX_CERTIFICATE)
        instance_file = reader(ntpath.join(ctx.identity_dir, "instance-id"), 128)
        cert = cert_file.read(MAX_CERTIFICATE)
        instance = protocol.identifier(instance_file.read(128).decode("ascii"), "original local instance")
        local = cert, identity.device_id(cert), hashlib.sha256(cert).hexdigest(), instance
        path = ntpath.join(ctx.jobs, LEDGER)
        ledger = reader(path, MAX_DB)
        _check_no_sidecars(api, path)
        header = ledger.read(100)
        if (len(header) != 100 or header[:16] != b"SQLite format 3\x00" or header[18:20] != b"\x01\x01"
                or header[24:28] != header[92:96]):
            _unavailable()
        result = _read_only(path, local)
        _check_no_sidecars(api, path)
        for still_held in (ledger, cert_file, instance_file):
            still_held.verify_unchanged()
        _check_config(ctx, settings)
        if require_owner(ctx) != owner:
            _unavailable()
        return result


def _status(row):
    return "本机已撤销" if row[2] else "本机保留配对"


def offer(ctx, settings):
    from desktop_owner_entry import _dialog

    if not getattr(sys, "frozen", False):
        return
    title = "本机配对记录"
    disclaimer = "只表示本机保存的状态。未核对手机端是否收到、当前连接或任何权限。"
    try:
        owner = require_owner(ctx)
    except (OSError, ValueError, PlatformError):
        return

    def retire():
        try:
            return require_owner(ctx) != owner  # The dialog timer never rereads the ledger.
        except (OSError, ValueError, PlatformError):
            return True

    try:
        rows, partial = _capture(ctx, settings, owner)
        if retire():
            return
        _check_config(ctx, settings)
    except (OSError, ValueError, RuntimeError, UnsupportedAlgorithm, PlatformError, sqlite3.Error):
        # Do not expose raw DB errors, paths, receipt or proof contents.
        if not retire():
            _dialog(title, "未能核对本机配对记录。这不表示没有配对。\n未启动服务或联系设备。", [("back", "返回工作台")], retire=retire)
        return

    body = disclaimer
    if partial:
        body += "\n只显示本次能核实的伙伴；其他来源、较早或超过上限的记录可能未列出。"
    elif not rows:
        body += "\n本机原账本没有已保存的伙伴记录。"
    width = next(n for n in range(6, 65, 2) if len({row[0][-n:] for row in rows}) == len(rows))
    choices = [(str(index), f"设备 {row[0][-width:]} · {_status(row)}") for index, row in enumerate(rows)]
    while True:
        if retire(): return
        selected = _dialog(title, body, [*choices, ("back", "返回工作台")], retire=retire)
        if selected is None or selected == "back" or retire(): return
        row = rows[int(selected)]
        if _dialog("本机保存的伙伴", f"{_status(row)}\n\n设备标识：{row[0]}\n证书指纹：{row[1]}\n\n{disclaimer}",
                   [("back", "返回配对记录")], retire=retire) != "back":
            return
