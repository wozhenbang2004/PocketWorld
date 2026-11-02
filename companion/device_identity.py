"""TLS-bound device identities and explicit, protected local identity provisioning.

Importing this module creates no key. Android uses AndroidKeyStore; Windows secrets
are protected by the current user's DPAPI. No invitation or certificate is authority.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import ctypes
import hashlib
import os
import re
import json
from pathlib import Path
import secrets
import ssl
import stat
import uuid

_TLS_PROOF = object()
_PAIRING_TLS_PROOF = object()


def spki_der(certificate_der):
    """Use PyCA's standard X509 parser for RFC5280 SubjectPublicKeyInfo.

    Import remains lazy so legacy Companion/stdlib TLS imports are unaffected.
    Enabling device identity requires the locked identity backend; absence fails
    explicitly instead of falling back to a handwritten certificate parser.
    """
    if not isinstance(certificate_der, bytes) or not 64 <= len(certificate_der) <= 16384:
        raise ValueError("Invalid device certificate size")
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
    except ImportError:
        raise RuntimeError("Device identity backend unavailable; install requirements-device-identity.txt") from None
    certificate = x509.load_der_x509_certificate(certificate_der)
    if certificate.public_bytes(serialization.Encoding.DER) != certificate_der:
        raise ValueError("Expected one exact DER certificate")
    return _p256_public_key(certificate).public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def _p256_public_key(certificate):
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import SignatureAlgorithmOID
    key = certificate.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1) or certificate.signature_algorithm_oid != SignatureAlgorithmOID.ECDSA_WITH_SHA256:
        raise ValueError("Device identity requires exact P-256 and SHA256withECDSA")
    return key


def device_id(certificate_der):
    return "pw1_" + hashlib.sha256(spki_der(certificate_der)).hexdigest()


def certificate_fingerprint(certificate_der):
    spki_der(certificate_der)
    return hashlib.sha256(certificate_der).hexdigest()


class AuthenticatedPeer:
    __slots__ = ("device_id", "certificate_fingerprint", "session_id", "certificate_der")

    def __init__(self, proof, certificate_der, session_id):
        if proof is not _TLS_PROOF:
            raise TypeError("Use authenticated_peer on a completed mutual TLS channel")
        self.certificate_der = bytes(certificate_der)
        self.device_id = device_id(self.certificate_der)
        self.certificate_fingerprint = certificate_fingerprint(self.certificate_der)
        self.session_id = session_id


class VerifiedPairingTarget:
    """Exact-pinned server identity on onboarding only; never a business Principal."""
    __slots__ = ("device_id", "certificate_fingerprint", "certificate_der")

    def __init__(self, proof, certificate_der):
        if proof is not _PAIRING_TLS_PROOF:
            raise TypeError("Use verified_pairing_target on isolated onboarding TLS")
        self.certificate_der = bytes(certificate_der)
        self.device_id = device_id(self.certificate_der)
        self.certificate_fingerprint = certificate_fingerprint(self.certificate_der)


def verified_pairing_target(channel, invitation_certificate_der):
    if not isinstance(channel, (ssl.SSLObject, ssl.SSLSocket)) or channel.version() not in ("TLSv1.2", "TLSv1.3") or channel.context.verify_mode != ssl.CERT_REQUIRED:
        raise ValueError("Verified isolated onboarding TLS required")
    certificate = channel.getpeercert(binary_form=True)
    if not certificate or not secrets.compare_digest(certificate, invitation_certificate_der):
        raise ValueError("Onboarding target differs from exact scanned certificate")
    return VerifiedPairingTarget(_PAIRING_TLS_PROOF, certificate)


def authenticated_peer(channel):
    if not isinstance(channel, (ssl.SSLObject, ssl.SSLSocket)) or channel.version() not in ("TLSv1.2", "TLSv1.3"):
        raise ValueError("Completed TLS channel required")
    if channel.context.verify_mode != ssl.CERT_REQUIRED:
        raise ValueError("Mutual certificate verification required")
    der = channel.getpeercert(binary_form=True)
    if not der:
        raise ValueError("Authenticated peer certificate required")
    # A native handle nonce, not a credential. TLS1.3 session.id may be empty
    # until a NewSessionTicket arrives and must not serve as channel authority.
    session_id = uuid.uuid4().hex
    return AuthenticatedPeer(_TLS_PROOF, der, session_id)


def pinned_context(peer_certificate_der, *, server_side=False):
    """Use exactly the paired certificate as a platform trust anchor, never CERT_NONE.

    Device identities use public-key pins, not DNS names. The caller MUST compare
    authenticated_peer.certificate_fingerprint to the expected pin after handshake.
    Disabling DNS hostname matching here does not disable X509 chain verification.
    """
    spki_der(peer_certificate_der)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER if server_side else ssl.PROTOCOL_TLS_CLIENT)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.check_hostname = False
    context.verify_mode = ssl.CERT_REQUIRED
    context.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(peer_certificate_der))
    if hasattr(ssl, "VERIFY_X509_PARTIAL_CHAIN"):
        context.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
    return context


def require_pin(peer, expected_certificate_der):
    if not isinstance(peer, AuthenticatedPeer) or not secrets.compare_digest(peer.certificate_fingerprint, certificate_fingerprint(expected_certificate_der)):
        raise ValueError("Paired device certificate changed")
    return peer


def _dpapi(value, decrypt=False):
    if os.name != "nt":
        raise RuntimeError("Windows DPAPI is required")
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]

    raw = ctypes.create_string_buffer(value)
    source = Blob(len(value), ctypes.cast(raw, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    function = crypt32.CryptUnprotectData if decrypt else crypt32.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    # CRYPTPROTECT_UI_FORBIDDEN; no machine-scope flag: another user cannot unwrap it.
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError(ctypes.get_last_error(), "DPAPI identity protection failed")
    try:
        return ctypes.string_at(target.pbData, target.cbData)
    finally:
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        kernel32.LocalFree(ctypes.cast(target.pbData, ctypes.c_void_p))


def _private_dir(path):
    path = Path(path)
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or not path.is_dir():
        raise ValueError("Identity directory must be a real private directory")
    if os.name == "nt":
        _restrict_windows_directory(path)
        return path
    if path.stat().st_uid != os.getuid() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise PermissionError("Identity directory must be owner-only (0700)")
    return path


def _restrict_windows_directory(path):
    """Protected inheritable DACL: current user and SYSTEM only, before key material."""
    from ctypes import wintypes
    advapi = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.GetFileAttributesW.argtypes = [wintypes.LPCWSTR]
    kernel.GetFileAttributesW.restype = wintypes.DWORD
    attributes = kernel.GetFileAttributesW(str(path))
    if attributes == 0xFFFFFFFF or attributes & 0x400:  # FILE_ATTRIBUTE_REPARSE_POINT
        raise PermissionError("Identity directory cannot be a reparse point")
    token = wintypes.HANDLE()
    advapi.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    advapi.OpenProcessToken.restype = wintypes.BOOL
    if not advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise OSError(ctypes.get_last_error(), "Cannot inspect current identity owner")
    try:
        length = wintypes.DWORD()
        advapi.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        advapi.GetTokenInformation.restype = wintypes.BOOL
        advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(length))  # TokenUser
        buffer = ctypes.create_string_buffer(length.value)
        if not advapi.GetTokenInformation(token, 1, buffer, length, ctypes.byref(length)):
            raise OSError(ctypes.get_last_error(), "Cannot inspect current identity SID")
        sid = ctypes.cast(buffer, ctypes.POINTER(ctypes.c_void_p))[0]
        sid_text = wintypes.LPWSTR()
        advapi.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
        advapi.ConvertSidToStringSidW.restype = wintypes.BOOL
        if not advapi.ConvertSidToStringSidW(sid, ctypes.byref(sid_text)):
            raise OSError(ctypes.get_last_error(), "Cannot encode identity owner SID")
        descriptor = ctypes.c_void_p()
        try:
            sddl = "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;" + sid_text.value + ")"
            advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
            advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL
            if not advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, ctypes.byref(descriptor), None):
                raise OSError(ctypes.get_last_error(), "Cannot create protected identity ACL")
            advapi.SetFileSecurityW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
            advapi.SetFileSecurityW.restype = wintypes.BOOL
            if not advapi.SetFileSecurityW(str(path), 0x80000004, descriptor):  # PROTECTED_DACL | DACL
                raise OSError(ctypes.get_last_error(), "Cannot protect local identity directory")
        finally:
            kernel.LocalFree.argtypes = [ctypes.c_void_p]
            if descriptor:
                kernel.LocalFree(descriptor)
            kernel.LocalFree(ctypes.cast(sid_text, ctypes.c_void_p))
    finally:
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle(token)


def _regular_owned_file(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
            or getattr(info, "st_file_attributes", 0) & 0x400
            or os.name != "nt" and (info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077)):
        raise PermissionError("Unsafe identity TLS temporary file")
    return info


@contextmanager
def _tls_temp_lock(stream):
    """Cross-process live-loader fence. A crash releases this OS lock."""
    acquired = False
    try:
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                acquired = True
            except OSError:
                pass
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
            except BlockingIOError:
                pass
        yield acquired
    finally:
        if acquired:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_UN)


def cleanup_tls_temporaries(directory, certificate_der):
    """Bounded, non-recursive cleanup of provably owned TLS load leftovers.

    A similarly named user directory, a reparse point, links, or an unknown file
    is NEVER recursively removed. Returns counts only, never secret paths/bytes.
    """
    root = Path(directory)
    if root.is_symlink() or not root.is_dir() or getattr(root.lstat(), "st_file_attributes", 0) & 0x400:
        raise PermissionError("Unsafe identity directory")
    fingerprint = certificate_fingerprint(certificate_der)
    counts = {"cleaned": 0, "busy": 0, "unsafe": 0}
    candidates = [path for path in root.iterdir() if re.fullmatch(r"tls-key-v1-[a-f0-9]{32}|tls-[a-z0-9_]{8}", path.name)]
    if len(candidates) > 64:
        raise PermissionError("Too many identity TLS leftovers; local recovery required")
    for path in candidates:
        try:
            info = path.lstat()
            if (not stat.S_ISDIR(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400
                    or os.name != "nt" and (info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077)):
                raise PermissionError("Unsafe TLS temporary directory")
            names = {entry.name for entry in path.iterdir()}
            if path.name.startswith("tls-key-v1-"):
                if not names <= {"owner.lock", "key.pem", "cert.pem"} or "owner.lock" not in names:
                    raise PermissionError("Unknown TLS temporary contents")
                for name in names:
                    _regular_owned_file(path / name)
                with (path / "owner.lock").open("r+b") as lock:
                    with _tls_temp_lock(lock) as owned:
                        if not owned:
                            counts["busy"] += 1
                            continue
                        lock.seek(0)
                        record = json.loads(lock.read(257))
                        if record != {"purpose": "pocketworld-tls-key-v1", "identity": fingerprint, "nonce": path.name[11:]}:
                            raise PermissionError("TLS temporary ownership mismatch")
                        for name in ("key.pem", "cert.pem"):
                            if name in names:
                                (path / name).unlink()
                (path / "owner.lock").unlink()
            else:
                # Legacy plaintext crash residue: prove exact application identity,
                # not just a tls-* prefix, before deleting the two known files.
                if names != {"key.pem", "cert.pem"}:
                    raise PermissionError("Unknown legacy TLS temporary contents")
                for name in names:
                    _regular_owned_file(path / name)
                if (path / "cert.pem").stat().st_size > 24000 or (path / "key.pem").stat().st_size > 16000:
                    raise PermissionError("Oversized legacy TLS temporary")
                pem = (path / "cert.pem").read_text(encoding="ascii")
                if ssl.PEM_cert_to_DER_cert(pem) != certificate_der:
                    raise PermissionError("Legacy TLS temporary belongs to another identity")
                from cryptography.hazmat.primitives import serialization
                from cryptography import x509
                key = serialization.load_pem_private_key((path / "key.pem").read_bytes(), password=None)
                fmt = (serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
                if key.public_key().public_bytes(*fmt) != x509.load_der_x509_certificate(certificate_der).public_key().public_bytes(*fmt):
                    raise PermissionError("Legacy TLS key does not match identity")
                (path / "key.pem").unlink()
                (path / "cert.pem").unlink()
            path.rmdir()
            counts["cleaned"] += 1
        except (OSError, ValueError, TypeError, KeyError):
            counts["unsafe"] += 1
    return counts


class LocalIdentity:
    def __init__(self, directory):
        self.directory = _private_dir(directory)
        self.certificate_der = (self.directory / "certificate.der").read_bytes()
        self.device_id = device_id(self.certificate_der)
        self.certificate_fingerprint = certificate_fingerprint(self.certificate_der)
        self.instance_id = (self.directory / "instance-id").read_text(encoding="ascii")
        uuid.UUID(self.instance_id)

    def _key(self):
        name = "private-key.dpapi" if os.name == "nt" else "private-key.pem"
        path = self.directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Missing protected device key")
        if os.name != "nt" and (path.stat().st_uid != os.getuid() or stat.S_IMODE(path.stat().st_mode) & 0o077):
            raise PermissionError("Device key must be owner-only (0600)")
        raw = path.read_bytes()
        return _dpapi(raw, True) if os.name == "nt" else raw

    def load_context(self, context):
        # CPython/OpenSSL accepts an encrypted PKCS8 file and password callback.
        # A random password exists ONLY in this call's memory; even abrupt process
        # death cannot leave an unencrypted Windows private-key copy on disk.
        from cryptography.hazmat.primitives import serialization
        cleanup = cleanup_tls_temporaries(self.directory, self.certificate_der)
        if cleanup["unsafe"]:
            raise PermissionError("Unsafe identity TLS leftovers require explicit local recovery")
        key = serialization.load_pem_private_key(self._key(), password=None)
        password = secrets.token_urlsafe(48).encode("ascii")
        encrypted = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.BestAvailableEncryption(password))
        nonce = uuid.uuid4().hex
        scratch = self.directory / ("tls-key-v1-" + nonce)
        scratch.mkdir(mode=0o700)
        if os.name == "nt":
            _restrict_windows_directory(scratch)
        owner = scratch / "owner.lock"
        fd = os.open(owner, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        try:
            with os.fdopen(fd, "w+b") as lock:
                record = {"purpose": "pocketworld-tls-key-v1", "identity": self.certificate_fingerprint, "nonce": nonce}
                lock.write(json.dumps(record, separators=(",", ":")).encode("ascii"))
                lock.flush()
                with _tls_temp_lock(lock) as held:
                    if not held:
                        raise PermissionError("Cannot lock private TLS loader")
                    key_path, cert_path = scratch / "key.pem", scratch / "cert.pem"
                    for path, content in ((key_path, encrypted), (cert_path, ssl.DER_cert_to_PEM_cert(self.certificate_der).encode("ascii"))):
                        out = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                        with os.fdopen(out, "wb") as stream:
                            stream.write(content)
                            stream.flush()
                            os.fsync(stream.fileno())
                    context.load_cert_chain(cert_path, key_path, password=lambda: password)
        finally:
            # Do not recursively delete unknown contents or follow links.
            cleanup_tls_temporaries(self.directory, self.certificate_der)

    def sign(self, value):
        # Optional reviewed crypto backend is required only for explicit pairing /
        # rotation signatures, never for ordinary stdlib transport imports.
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        key = serialization.load_pem_private_key(self._key(), password=None)
        return key.sign(value, ec.ECDSA(hashes.SHA256()))


def load_existing_identity(directory):
    """Startup/explicit enable may load existing credentials; never provisions missing keys."""
    directory = Path(directory)
    names = ("certificate.der", "instance-id", "private-key.dpapi" if os.name == "nt" else "private-key.pem")
    if not directory.is_dir() or directory.is_symlink() or any(not (directory / name).is_file() or (directory / name).is_symlink() for name in names):
        raise FileNotFoundError("Existing protected device identity is unavailable; explicit owner onboarding is required")
    return LocalIdentity(directory)


def provision_identity(directory):
    """Explicit native onboarding only. Never called at import/request admission.

    cryptography supplies standard X509/P-256 generation; no home-grown crypto.
    Files are local credentials and MUST NOT enter Git, logs, artifacts or backup.
    """
    import datetime
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    directory = _private_dir(directory)
    if any(directory.iterdir()):
        raise FileExistsError("Identity provisioning requires a new empty private directory")
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PocketWorld Device")])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1825)).sign(key, hashes.SHA256()))
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    files = {"certificate.der": cert.public_bytes(serialization.Encoding.DER), "instance-id": str(uuid.uuid4()).encode("ascii")}
    files["private-key.dpapi" if os.name == "nt" else "private-key.pem"] = _dpapi(private) if os.name == "nt" else private
    for name, raw in files.items():
        descriptor = os.open(directory / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    return LocalIdentity(directory)


def verify_signature(certificate_der, message, signature):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, utils
    spki_der(certificate_der)  # one bounded exact DER certificate and exact algorithm
    key = _p256_public_key(x509.load_der_x509_certificate(certificate_der))
    if not isinstance(signature, bytes) or not 8 <= len(signature) <= 72:
        raise ValueError("Invalid P-256 DER signature size")
    r, s = utils.decode_dss_signature(signature)
    if utils.encode_dss_signature(r, s) != signature or not 0 < r < key.curve.group_order or not 0 < s < key.curve.group_order:
        raise ValueError("Non-canonical P-256 DER signature")
    key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
