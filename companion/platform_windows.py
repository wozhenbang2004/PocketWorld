"""Windows 11 local primitives, using real Win32 handles and Job Objects.

No GUI injection is implemented here. No shell/PowerShell wrapping, drive-relative
paths, UNC/SMB workspaces or Windows SSH second hop are silently enabled.
"""
from __future__ import annotations

from contextlib import contextmanager
import ctypes
import hashlib
import ntpath
import os
import re
import subprocess
import threading
import time
import uuid

from platform_backend import NativeProcessStartError, PlatformError, bytes_version

LIMIT = 262144
RESERVED = re.compile(r"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$", re.I)


def relative_parts(value, empty=False):
    if empty and value == "":
        return []
    if not isinstance(value, str) or not value or len(value) > 2048 or "\\" in value or "\x00" in value:
        raise PlatformError("A slash-separated workspace-relative path is required", 400)
    parts = value.split("/")
    if any(not part or part in {".", ".."} or part[-1] in ". " or RESERVED.fullmatch(part)
           or any(ord(c) < 32 or c in '<>:"|?*' for c in part) for part in parts):
        raise PlatformError("Windows path escapes, streams and reserved aliases are not accepted", 403)
    return parts


def local_root(value):
    if not isinstance(value, str) or not re.match(r"^[A-Za-z]:[\\/]", value) or "\x00" in value:
        raise PlatformError("Windows workspace must be an absolute local drive path; UNC/SMB and device paths are unsupported", 409)
    drive, tail = ntpath.splitdrive(value)
    parts = tail.replace("\\", "/").strip("/")
    clean = relative_parts(parts, empty=True)
    root = drive.upper() + "\\" + "\\".join(clean)
    if len(root) > 240:
        raise PlatformError("Windows workspace root exceeds the supported path bound", 409)
    return root.rstrip("\\") if clean else root


class Win32:
    """Lazily loaded so Linux can validate contracts without claiming Windows I/O."""
    def __init__(self):
        if os.name != "nt":
            raise PlatformError("Native Windows APIs are unavailable on this host")
        from ctypes import wintypes as w
        self.w = w
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        class FileInfo(ctypes.Structure):
            _fields_ = [("attributes", w.DWORD), ("created", w.FILETIME), ("accessed", w.FILETIME),
                        ("written", w.FILETIME), ("volume", w.DWORD), ("sizeHigh", w.DWORD),
                        ("sizeLow", w.DWORD), ("links", w.DWORD), ("indexHigh", w.DWORD), ("indexLow", w.DWORD)]
        self.FileInfo = FileInfo
        signatures = {
            "CreateFileW": ([w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.HANDLE], w.HANDLE),
            "CloseHandle": ([w.HANDLE], w.BOOL),
            "GetFileInformationByHandle": ([w.HANDLE, ctypes.POINTER(FileInfo)], w.BOOL),
            "GetFinalPathNameByHandleW": ([w.HANDLE, w.LPWSTR, w.DWORD, w.DWORD], w.DWORD),
            "ReadFile": ([w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p], w.BOOL),
            "WriteFile": ([w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p], w.BOOL),
            "FlushFileBuffers": ([w.HANDLE], w.BOOL),
            "MoveFileExW": ([w.LPCWSTR, w.LPCWSTR, w.DWORD], w.BOOL),
            "ReplaceFileW": ([w.LPCWSTR, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_void_p, ctypes.c_void_p], w.BOOL),
            "DeleteFileW": ([w.LPCWSTR], w.BOOL),
            "CreateDirectoryW": ([w.LPCWSTR, ctypes.c_void_p], w.BOOL),
            "GetDriveTypeW": ([w.LPCWSTR], w.UINT),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.k, name)
            function.argtypes, function.restype = args, result

    def checked(self, value):
        if not value:
            raise ctypes.WinError(ctypes.get_last_error())
        return value

    def open(self, path, *, directory=False, create=False, write=False, delete=False):
        # No FILE_SHARE_WRITE or DELETE: the directory object cannot be renamed,
        # exchanged or changed to a junction while descendants are opened.
        flags = 0x00200000 | (0x02000000 if directory else 0)  # OPEN_REPARSE_POINT, BACKUP_SEMANTICS
        access = 0x80000000 | (0x40000000 if write else 0) | (0x10000 if delete else 0)
        handle = self.k.CreateFileW(path, access, 0x1 if not write else 0, None, 1 if create else 3, flags, None)
        if handle == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        return handle

    def info(self, handle):
        info = self.FileInfo()
        self.checked(self.k.GetFileInformationByHandle(handle, ctypes.byref(info)))
        return info

    def final(self, handle):
        buffer = ctypes.create_unicode_buffer(32768)
        length = self.k.GetFinalPathNameByHandleW(handle, buffer, len(buffer), 0)
        if not length or length >= len(buffer):
            raise PlatformError("Final Windows handle path is unavailable", 403)
        return buffer.value

    @staticmethod
    def identity(info):
        return [info.volume, info.indexHigh, info.indexLow]

    def read(self, handle, maximum=LIMIT):
        info = self.info(handle)
        if info.attributes & (0x10 | 0x400) or info.links != 1:
            raise PlatformError("Only a regular, single-link, non-reparse file may be read", 403)
        if (info.sizeHigh << 32) + info.sizeLow > maximum:
            raise PlatformError("File exceeds the existing transfer bound", 413)
        buffer, count = ctypes.create_string_buffer(maximum + 1), self.w.DWORD()
        self.checked(self.k.ReadFile(handle, buffer, maximum + 1, ctypes.byref(count), None))
        if count.value > maximum:
            raise PlatformError("File exceeds the existing transfer bound", 413)
        return buffer.raw[:count.value]

    def write(self, handle, value):
        buffer, count = ctypes.create_string_buffer(value), self.w.DWORD()
        self.checked(self.k.WriteFile(handle, buffer, len(value), ctypes.byref(count), None))
        if count.value != len(value):
            raise PlatformError("Windows did not write all bytes")
        self.checked(self.k.FlushFileBuffers(handle))

    def disposition(self, handle):
        class Disposition(ctypes.Structure):
            _fields_ = [("delete", ctypes.c_ubyte)]
        value = Disposition(1)
        function = self.k.SetFileInformationByHandle
        function.argtypes, function.restype = [self.w.HANDLE, ctypes.c_int, ctypes.c_void_p, self.w.DWORD], self.w.BOOL
        self.checked(function(handle, 4, ctypes.byref(value), ctypes.sizeof(value)))

    def publish_handle(self, handle, directory_handle, name):
        """Exact source handle, fixed parent handle, absent-only FILE_RENAME_INFO."""
        class Rename(ctypes.Structure):
            _fields_ = [("flags", self.w.DWORD), ("root", self.w.HANDLE), ("length", self.w.DWORD), ("name", self.w.WCHAR * 1)]
        encoded = name.encode("utf-16-le")
        buffer = ctypes.create_string_buffer(Rename.name.offset + len(encoded))
        header = ctypes.cast(buffer, ctypes.POINTER(Rename)).contents
        header.flags, header.root, header.length = 0, directory_handle, len(encoded)
        ctypes.memmove(ctypes.addressof(buffer) + Rename.name.offset, encoded, len(encoded))
        function = self.k.SetFileInformationByHandle
        function.argtypes, function.restype = [self.w.HANDLE, ctypes.c_int, ctypes.c_void_p, self.w.DWORD], self.w.BOOL
        self.checked(function(handle, 3, buffer, len(buffer)))  # FileRenameInfo, ReplaceIfExists=FALSE

    def create_private_directory(self, path, user_sid):
        """Absent-only directory with owner/SYSTEM DACL supplied at creation, before bytes."""
        if not re.fullmatch(r"S-1-[0-9-]+", user_sid):
            raise PlatformError("Native current user SID required")
        class SecurityAttributes(ctypes.Structure):
            _fields_ = [("length", self.w.DWORD), ("descriptor", ctypes.c_void_p), ("inherit", self.w.BOOL)]
        advapi = ctypes.WinDLL("advapi32", use_last_error=True)
        convert = advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW
        convert.argtypes = [self.w.LPCWSTR, self.w.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(self.w.DWORD)]
        convert.restype = self.w.BOOL
        descriptor = ctypes.c_void_p()
        self.checked(convert("O:" + user_sid + "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;" + user_sid + ")", 1, ctypes.byref(descriptor), None))
        try:
            attributes = SecurityAttributes(ctypes.sizeof(SecurityAttributes), descriptor, False)
            self.checked(self.k.CreateDirectoryW(path, ctypes.byref(attributes)))
        finally:
            self.k.LocalFree.argtypes, self.k.LocalFree.restype = [ctypes.c_void_p], ctypes.c_void_p
            self.k.LocalFree(descriptor)

    def private_access(self, handle, user_sid, *, require_protected=False):
        """Read the ORIGINAL object's owner/DACL; never repair permissions on serve.

        Strict current-user/SYSTEM allow ACE policy, not an assertion of sandbox
        isolation against administrators or arbitrary same-user native code.
        """
        if not isinstance(user_sid, str) or not re.fullmatch(r"S-1-[0-9-]{1,180}", user_sid):
            raise PlatformError("Native current-user SID required for private state", 403)
        w, advapi = self.w, ctypes.WinDLL("advapi32", use_last_error=True)
        p = ctypes.POINTER(ctypes.c_void_p)
        advapi.GetSecurityInfo.argtypes, advapi.GetSecurityInfo.restype = [w.HANDLE, ctypes.c_int, w.DWORD, p, p, p, p, p], w.DWORD
        advapi.GetSecurityDescriptorControl.argtypes, advapi.GetSecurityDescriptorControl.restype = [ctypes.c_void_p, ctypes.POINTER(w.WORD), ctypes.POINTER(w.DWORD)], w.BOOL
        advapi.IsValidAcl.argtypes, advapi.IsValidAcl.restype = [ctypes.c_void_p], w.BOOL
        advapi.IsValidSid.argtypes, advapi.IsValidSid.restype = [ctypes.c_void_p], w.BOOL
        advapi.GetAce.argtypes, advapi.GetAce.restype = [ctypes.c_void_p, w.DWORD, p], w.BOOL
        advapi.ConvertSidToStringSidW.argtypes, advapi.ConvertSidToStringSidW.restype = [ctypes.c_void_p, ctypes.POINTER(w.LPWSTR)], w.BOOL
        self.k.LocalFree.argtypes, self.k.LocalFree.restype = [ctypes.c_void_p], ctypes.c_void_p
        owner, dacl, descriptor = ctypes.c_void_p(), ctypes.c_void_p(), ctypes.c_void_p()
        code = advapi.GetSecurityInfo(handle, 1, 1 | 4, ctypes.byref(owner), None, ctypes.byref(dacl), None, ctypes.byref(descriptor))
        if code:
            raise PlatformError("Original private-state owner/DACL cannot be verified", 403)
        def sid_text(pointer):
            if not pointer or not advapi.IsValidSid(pointer):
                raise PlatformError("Private-state SID is invalid", 403)
            result = w.LPWSTR()
            self.checked(advapi.ConvertSidToStringSidW(pointer, ctypes.byref(result)))
            try:
                return result.value
            finally:
                self.k.LocalFree(ctypes.cast(result, ctypes.c_void_p))
        try:
            if sid_text(owner) != user_sid or not dacl or not advapi.IsValidAcl(dacl):
                raise PlatformError("Private state must have the current owner and a non-null valid DACL", 403)
            control, revision = w.WORD(), w.DWORD()
            self.checked(advapi.GetSecurityDescriptorControl(descriptor, ctypes.byref(control), ctypes.byref(revision)))
            if require_protected and not control.value & 0x1000:  # SE_DACL_PROTECTED
                raise PlatformError("Private installation/state root must have a protected DACL", 403)
            class Acl(ctypes.Structure):
                _fields_ = [("revision", ctypes.c_ubyte), ("reserved", ctypes.c_ubyte), ("size", w.WORD), ("count", w.WORD), ("reserved2", w.WORD)]
            class Ace(ctypes.Structure):
                _fields_ = [("kind", ctypes.c_ubyte), ("flags", ctypes.c_ubyte), ("size", w.WORD)]
            acl = ctypes.cast(dacl, ctypes.POINTER(Acl)).contents
            if not 0 < acl.count <= 128:
                raise PlatformError("Private-state DACL is empty or exceeds its bound", 403)
            allowed = set()
            for index in range(acl.count):
                entry = ctypes.c_void_p()
                self.checked(advapi.GetAce(dacl, index, ctypes.byref(entry)))
                header = ctypes.cast(entry, ctypes.POINTER(Ace)).contents
                if header.kind not in (0, 1) or header.size < 12 or not (dacl.value + ctypes.sizeof(Acl) <= entry.value < dacl.value + acl.size) or entry.value + header.size > dacl.value + acl.size:
                    raise PlatformError("Unsupported private-state ACE; local owner review required", 403)
                principal = sid_text(entry.value + 8)  # ACCESS_ALLOWED/DENIED_ACE SidStart
                if header.kind == 0:
                    if principal not in {user_sid, "S-1-5-18"}:
                        raise PlatformError("Private state grants access beyond its current owner/SYSTEM", 403)
                    allowed.add(principal)
            if user_sid not in allowed:
                raise PlatformError("Private-state owner allow ACE is missing", 403)
            return {"ownerSid": user_sid, "protectedDacl": bool(control.value & 0x1000), "allowPrincipals": sorted(allowed)}
        finally:
            if descriptor:
                self.k.LocalFree(descriptor)


class WindowsBackend:
    name = "windows"

    def __init__(self, api=None):
        self.api = api if api is not None else Win32()

    def capabilities(self):
        return {"platform": self.name, "command": True, "boundedFiles": True,
                "rawConfigCas": True, "configConsistency": "optimistic_raw_bytes_cas",
                "processTree": "creation_time_job_list_and_kill_on_close",
                "uncWorkspaces": False, "windowsSshSecondHop": False,
                "selectedInputs": False, "projectHistory": False, "assignmentWorkspace": False,
                "nativeWindowsRuntimeVerified": False}

    def assert_private_directory(self, root, user_sid, *, require_protected=True):
        with self.directory(root) as (_, handle, _identity):
            return self.api.private_access(handle, user_sid, require_protected=require_protected)

    def read_private_bytes(self, root, relative, user_sid, *, maximum=LIMIT):
        parts = relative_parts(relative)
        with self.directory(root, parts[:-1]) as (directory, _, _identity):
            target = ntpath.join(directory, parts[-1])
            handle = self.api.open(target)
            try:
                if self.api.final(handle).casefold() != ("\\\\?\\" + target).casefold():
                    raise PlatformError("Private state handle escaped its reviewed path", 403)
                self.api.private_access(handle, user_sid)
                return self.api.read(handle, maximum)
            finally:
                self.api.k.CloseHandle(handle)

    def process_identity(self, pid):
        """Actual PID generation/session; access failure is not exit evidence."""
        if type(pid) is not int or not 1 <= pid <= 0xFFFFFFFF:
            raise PlatformError("Invalid original Windows process identifier", 400)
        k, w = self.api.k, self.api.w
        k.OpenProcess.argtypes, k.OpenProcess.restype = [w.DWORD, w.BOOL, w.DWORD], w.HANDLE
        k.GetProcessTimes.argtypes, k.GetProcessTimes.restype = [w.HANDLE] + [ctypes.POINTER(w.FILETIME)] * 4, w.BOOL
        k.ProcessIdToSessionId.argtypes, k.ProcessIdToSessionId.restype = [w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL
        k.WaitForSingleObject.argtypes, k.WaitForSingleObject.restype = [w.HANDLE, w.DWORD], w.DWORD
        handle = k.OpenProcess(0x1000 | 0x100000, False, pid)
        if not handle:
            code = ctypes.get_last_error()
            return {"available": code == 87, "exists": False if code == 87 else None, "pid": pid,
                    "reason": "original_pid_absent" if code == 87 else "process_identity_access_unavailable"}
        try:
            created, exited, kernel, user, session = w.FILETIME(), w.FILETIME(), w.FILETIME(), w.FILETIME(), w.DWORD()
            if not k.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user)) or not k.ProcessIdToSessionId(pid, ctypes.byref(session)):
                return {"available": False, "exists": True, "pid": pid, "reason": "process_generation_unavailable"}
            wait = k.WaitForSingleObject(handle, 0)
            if wait not in (0, 0x102):
                return {"available": False, "exists": True, "pid": pid, "reason": "process_exit_state_unavailable"}
            return {"available": True, "exists": True, "pid": pid, "created": str((created.dwHighDateTime << 32) | created.dwLowDateTime),
                    "sessionId": session.value, "exited": wait == 0}
        finally:
            k.CloseHandle(handle)

    def logon_context(self):
        """Current native logon/session facts, not an environment username."""
        w, k = self.api.w, self.api.k
        advapi = ctypes.WinDLL("advapi32", use_last_error=True)
        k.GetCurrentProcess.argtypes, k.GetCurrentProcess.restype = [], w.HANDLE
        advapi.OpenProcessToken.argtypes, advapi.OpenProcessToken.restype = [w.HANDLE, w.DWORD, ctypes.POINTER(w.HANDLE)], w.BOOL
        advapi.GetTokenInformation.argtypes, advapi.GetTokenInformation.restype = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL
        advapi.ConvertSidToStringSidW.argtypes, advapi.ConvertSidToStringSidW.restype = [ctypes.c_void_p, ctypes.POINTER(w.LPWSTR)], w.BOOL
        k.LocalFree.argtypes, k.LocalFree.restype = [ctypes.c_void_p], ctypes.c_void_p
        class Luid(ctypes.Structure):
            _fields_ = [("low", w.DWORD), ("high", w.LONG)]
        class Statistics(ctypes.Structure):
            _fields_ = [("tokenId", Luid), ("authenticationId", Luid), ("expiration", ctypes.c_int64),
                        ("tokenType", ctypes.c_int), ("impersonation", ctypes.c_int), ("dynamicCharged", w.DWORD),
                        ("dynamicAvailable", w.DWORD), ("groups", w.DWORD), ("privileges", w.DWORD), ("modified", Luid)]
        token, length = w.HANDLE(), w.DWORD()
        self.api.checked(advapi.OpenProcessToken(k.GetCurrentProcess(), 8, ctypes.byref(token)))
        try:
            advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(length))
            buffer = ctypes.create_string_buffer(length.value)
            self.api.checked(advapi.GetTokenInformation(token, 1, buffer, length.value, ctypes.byref(length)))
            sid = ctypes.cast(buffer, ctypes.POINTER(ctypes.c_void_p))[0]
            text = w.LPWSTR()
            self.api.checked(advapi.ConvertSidToStringSidW(sid, ctypes.byref(text)))
            try:
                user_sid = text.value
            finally:
                k.LocalFree(ctypes.cast(text, ctypes.c_void_p))
            statistics = Statistics()
            self.api.checked(advapi.GetTokenInformation(token, 10, ctypes.byref(statistics), ctypes.sizeof(statistics), ctypes.byref(length)))
            process = self.process_identity(os.getpid())
            if not process.get("available") or not process.get("exists"):
                raise PlatformError("Current native recovery host identity is unavailable")
            return {"userSid": user_sid, "sessionId": process["sessionId"],
                    "authenticationId": "%08x:%08x" % (statistics.authenticationId.high & 0xFFFFFFFF, statistics.authenticationId.low)}
        finally:
            k.CloseHandle(token)

    def original_host_stopped(self, pid, created, session_id):
        actual = self.process_identity(pid)
        stopped = actual.get("available") is True and (actual.get("exists") is False or actual.get("created") != created
                                                       or actual.get("exited") is True)
        # A generation mismatch proves the ORIGINAL host exited, never licenses
        # killing the unrelated new process that reused its PID.
        return {"originalHostStoppedVerified": stopped, "method": "original_pid_creation_generation_and_exit",
                "originalPid": pid, "originalCreated": created, "originalSessionId": session_id,
                "currentProcessIdentity": actual}

    def named_job_stopped(self, name):
        if not isinstance(name, str) or not re.fullmatch(r"Local\\PocketWorldDesktop-[a-f0-9]{32}-[a-f0-9]{32}", name):
            return {"processGroupStoppedVerified": False, "reason": "original_named_job_binding_unavailable"}
        k, w = self.api.k, self.api.w
        k.OpenJobObjectW.argtypes, k.OpenJobObjectW.restype = [w.DWORD, w.BOOL, w.LPCWSTR], w.HANDLE
        k.QueryInformationJobObject.argtypes, k.QueryInformationJobObject.restype = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p], w.BOOL
        handle = k.OpenJobObjectW(4, False, name)  # JOB_OBJECT_QUERY, never terminate a guessed PID.
        if not handle:
            code = ctypes.get_last_error()
            return {"processGroupStoppedVerified": code == 2, "method": "original_named_job_object_destroyed" if code == 2 else "named_job_query_unavailable",
                    "reason": "object_absent" if code == 2 else "do_not_infer_stop_from_access_failure"}
        try:
            class Accounting(ctypes.Structure):
                _fields_ = [(field, ctypes.c_int64) for field in ("user", "kernel", "periodUser", "periodKernel")] + [(field, w.DWORD) for field in ("pageFaults", "total", "active", "terminated")]
            accounting = Accounting()
            if not k.QueryInformationJobObject(handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                return {"processGroupStoppedVerified": False, "method": "named_job_query_unavailable"}
            return {"processGroupStoppedVerified": accounting.active == 0, "activeProcesses": accounting.active,
                    "method": "original_named_job_object_active_processes_zero"}
        finally:
            k.CloseHandle(handle)

    @contextmanager
    def directory(self, root, parts=()):
        root = local_root(os.fspath(root))
        if self.api.k.GetDriveTypeW(root[:3]) != 3:  # DRIVE_FIXED only, no mapped SMB aliases.
            raise PlatformError("Windows native workspace requires a local fixed drive; mapped network/removable drives are unsupported", 409)
        relative_parts("/".join(parts), empty=True)
        path, handles = root[:3], []
        root_parts = root[3:].split("\\") if root[3:] else []
        try:
            # Lock every ancestor, including the volume root. No resolve() or
            # string-prefix-only check substitutes for checking these handles.
            for component in [None] + root_parts + list(parts):
                if component is not None:
                    path = ntpath.join(path, component)
                handle = self.api.open(path, directory=True)
                handles.append(handle)
                info = self.api.info(handle)
                if not info.attributes & 0x10 or info.attributes & 0x400:
                    raise PlatformError("Workspace ancestor is not a real non-reparse directory", 403)
                final = self.api.final(handle)
                expected = "\\\\?\\" + path.rstrip("\\")
                if final.rstrip("\\").casefold() != expected.casefold():
                    raise PlatformError("Windows handle did not bind the configured directory", 403)
            yield path, handles[-1], self.api.identity(self.api.info(handles[-1]))
        except OSError as failure:
            raise PlatformError("Windows directory is unavailable, shared for mutation, or unsafe", 403) from failure
        finally:
            for handle in reversed(handles):
                self.api.k.CloseHandle(handle)

    def root_binding(self, root):
        with self.directory(root) as (path, handle, identity):
            return hashlib.sha256(repr([self.api.final(handle).casefold(), identity]).encode()).hexdigest()

    def workspace_location(self, root, *, current=lambda: None):
        """Canonical display path from the SAME held, non-reparse native root."""
        with self.directory(root) as (_, handle, _identity):
            current()
            final = self.api.final(handle)
            if not final.startswith("\\\\?\\"):
                raise PlatformError("Original workspace has no approved local drive display path", 403)
            display = local_root(final[4:])
            current()
            return display

    def harness_executable_identity(self, executable):
        if not isinstance(executable, str) or not ntpath.isabs(executable) or ntpath.splitext(executable)[1].casefold() != ".exe":
            raise PlatformError("Explicit native Harness executable must be an absolute .exe path")
        executable = ntpath.join(local_root(ntpath.dirname(executable)), ntpath.basename(executable))
        relative_parts(ntpath.basename(executable))
        with self.directory(ntpath.dirname(executable)):
            handle = self.api.open(executable)
            try:
                info = self.api.info(handle)
                if info.attributes & (0x10 | 0x400) or info.links != 1 or self.api.final(handle).casefold() != ("\\\\?\\" + executable).casefold():
                    raise PlatformError("Harness executable is not the original regular single-link non-reparse image", 403)
                stamp = self.api.identity(info) + [info.sizeHigh, info.sizeLow, info.written.dwHighDateTime, info.written.dwLowDateTime]
                return executable, stamp
            finally:
                self.api.k.CloseHandle(handle)

    def read_bytes(self, root, relative, maximum=LIMIT):
        parts = relative_parts(relative)
        with self.directory(root, parts[:-1]) as (path, _, identity):
            target = ntpath.join(path, parts[-1])
            try:
                handle = self.api.open(target)
            except OSError as failure:
                if getattr(failure, "winerror", None) in (2, 3):
                    return None
                raise PlatformError("Windows file cannot be safely opened", 403) from failure
            try:
                if self.api.final(handle).casefold() != ("\\\\?\\" + target).casefold():
                    raise PlatformError("File handle is outside the exact reviewed path", 403)
                return self.api.read(handle, maximum)
            finally:
                self.api.k.CloseHandle(handle)

    @contextmanager
    def open_reader(self, root, relative, maximum=20 * 1024 * 1024):
        """Pinned regular file stream; parent and file mutation remain denied.

        The returned reader has read(size), seek(offset), size, identity and
        verify_unchanged().  This is suitable for the shared artifact chunker;
        callers still own authorization, content hashes, TTLs and provenance.
        """
        parts = relative_parts(relative)
        with self.directory(root, parts[:-1]) as (directory, _, identity):
            target = ntpath.join(directory, parts[-1])
            handle = self.api.open(target)
            try:
                if self.api.final(handle).casefold() != ("\\\\?\\" + target).casefold():
                    raise PlatformError("Artifact handle escaped the reviewed path", 403)
                reader = WindowsHandleReader(self.api, handle, maximum)
                yield reader
                reader.verify_unchanged()
            finally:
                self.api.k.CloseHandle(handle)

    def create_private_snapshot(self, root, relative, raw, maximum=20 * 1024 * 1024, *, current=lambda: None):
        """Publish an absent immutable cache object beneath an already-private root.

        State-root ACL provisioning is the installer's job, not inferred from
        its filename. This never grants permissions or overwrites an object.
        """
        if not isinstance(raw, bytes) or len(raw) > maximum:
            raise PlatformError("Snapshot exceeds its explicit byte bound", 413)
        parts = relative_parts(relative)
        with self.directory(root, parts[:-1]) as (directory, directory_handle, identity):
            temporary = ntpath.join(directory, ".pocketworld-capture-" + uuid.uuid4().hex)
            handle, attempted = None, False
            try:
                handle = self.api.open(temporary, create=True, write=True, delete=True)
                self.api.write(handle, raw)
                source_identity = self.api.identity(self.api.info(handle))
                current()  # Final original grant/cancel gate at the native publication boundary.
                attempted = True
                # No name reopening for the source and no overwrite option.
                self.api.publish_handle(handle, directory_handle, parts[-1])
                reader = WindowsHandleReader(self.api, handle, maximum)
                reader.seek(0)
                actual = hashlib.sha256()
                while True:
                    chunk = reader.read(262144)
                    if not chunk:
                        break
                    actual.update(chunk)
                if actual.hexdigest() != hashlib.sha256(raw).hexdigest():
                    raise PlatformError("Published original handle bytes changed; object requires inspection")
                self.api.k.CloseHandle(handle); handle = None
                with self.open_reader(root, relative, maximum) as reader:
                    if reader.identity != source_identity:
                        raise PlatformError("Published path no longer names the original snapshot; do not expose it")
                return {"bytes": len(raw), "sha256": actual.hexdigest(), "parentIdentity": identity, "fileIdentity": source_identity,
                        "publication": "original_handle_absent_only_rename"}
            finally:
                if handle is not None:
                    # A failed/unknown rename may already have published the
                    # object. Never delete it or a replacement by pathname.
                    try:
                        if not attempted:
                            self.api.disposition(handle)
                    finally:
                        self.api.k.CloseHandle(handle)

    def delete_private_snapshot(self, root, relative, expected_sha256, expected_bytes, current=lambda: None):
        """Delete only an exact owned cache object, through its checked file handle.

        No workspace delete API, recursive cleanup or path-based unlink. Hash
        mismatch, links, reparse points and concurrent open writers fail closed.
        """
        parts = relative_parts(relative)
        if len(parts) != 1 or not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\.[A-Za-z0-9_-]{1,80})?", parts[0]):
            raise PlatformError("Only a specifically owned UUID cache object may be deleted", 403)
        return self._delete_owned_bytes(root, relative, expected_sha256, expected_bytes, current)

    def delete_local_control_file(self, root, relative, expected_sha256, expected_bytes, current=lambda: None):
        # Only the local signed-recovery path uses this narrow marker cleanup.
        # It is never registered as a remote tool or general workspace delete.
        if relative != "desktop-local-stop":
            raise PlatformError("Only the original local desktop stop marker may be cleared", 403)
        return self._delete_owned_bytes(root, relative, expected_sha256, expected_bytes, current)

    def delete_harness_configuration(self, root, relative, before, original_identity, current):
        # Only HarnessConfigs' reviewed compensation plan can call this local
        # storage primitive. It is not exposed as a remote/general delete tool.
        if relative not in {"config.toml", "settings.json"} or not isinstance(before, bytes) or len(before) > LIMIT or original_identity is None:
            raise PlatformError("Original created Harness file identity/bytes are required for conditional deletion", 403)
        return self._delete_owned_bytes(root, relative, hashlib.sha256(before).hexdigest(), len(before), current, original_identity)

    def _delete_owned_bytes(self, root, relative, expected_sha256, expected_bytes, current, expected_identity=None):
        if not re.fullmatch(r"[a-f0-9]{64}", expected_sha256 or "") or type(expected_bytes) is not int or not 0 <= expected_bytes <= 20 * 1024 * 1024:
            raise PlatformError("Exact cached content hash and byte count required", 400)
        with self.directory(root) as (directory, _, identity):
            target = ntpath.join(directory, relative)
            handle = self.api.k.CreateFileW(target, 0x80000000 | 0x10000, 1, None, 3, 0x00200000, None)  # READ | DELETE, share READ only
            if handle == ctypes.c_void_p(-1).value:
                code = ctypes.get_last_error()
                if code == 2:
                    return {"status": "completed", "deleted": False, "alreadyAbsent": True, "verified": True}
                raise PlatformError("Owned cache object cannot be opened for exact handle deletion", 409)
            attempted = False
            try:
                if self.api.final(handle).casefold() != ("\\\\?\\" + target).casefold():
                    raise PlatformError("Cached object handle escaped its exact path", 403)
                reader = WindowsHandleReader(self.api, handle, 20 * 1024 * 1024)
                if expected_identity is not None and reader.identity != expected_identity:
                    raise PlatformError("Original owned object identity changed; nothing deleted", 409)
                if reader.size != expected_bytes:
                    raise PlatformError("Owned cache object size changed; nothing deleted", 409)
                digest = hashlib.sha256()
                while True:
                    chunk = reader.read(262144)
                    if not chunk:
                        break
                    digest.update(chunk)
                if digest.hexdigest() != expected_sha256:
                    raise PlatformError("Owned cache object hash changed; nothing deleted", 409)
                current()
                reader.verify_unchanged()
                class Disposition(ctypes.Structure):
                    _fields_ = [("delete", ctypes.c_ubyte)]
                value = Disposition(1)
                function = self.api.k.SetFileInformationByHandle
                function.argtypes, function.restype = [self.api.w.HANDLE, ctypes.c_int, ctypes.c_void_p, self.api.w.DWORD], self.api.w.BOOL
                attempted = True
                self.api.checked(function(handle, 4, ctypes.byref(value), ctypes.sizeof(value)))
            except (OSError, PlatformError):
                if not attempted:
                    raise
                return {"status": "unknown", "deleted": None, "verified": False, "message": "Original cache deletion needs inspection; do not replay against a replacement"}
            finally:
                self.api.k.CloseHandle(handle)
            # Closing this exact delete-pending handle removes the checked file.
            # A new file at the same name is never deleted by this operation.
            try:
                actual = self.read_bytes(root, relative, maximum=20 * 1024 * 1024)
            except (OSError, PlatformError):
                return {"status": "unknown", "deleted": None, "verified": False, "message": "Original handle was marked for deletion but final path inspection is unavailable"}
            return {"status": "completed" if actual is None else "unknown", "deleted": actual is None,
                    "verified": actual is None, "deletionMethod": "original_file_handle_disposition"}

    def _replacement_fact(self, root, relative):
        try:
            if self.read_bytes(root, relative) is None:
                return {"state": "absent"}
            with self.open_reader(root, relative, LIMIT) as reader:
                data = reader.read(LIMIT)
                return {"state": "present", "fileIdentity": reader.identity, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
        except FileNotFoundError:
            return {"state": "absent"}
        except (OSError, PlatformError):
            return {"state": "unavailable"}

    def compare_and_swap(self, root, relative, before, after, current=lambda: None, *, record_intent=lambda value: None):
        if (before is not None and not isinstance(before, bytes)) or not isinstance(after, bytes):
            raise PlatformError("Configuration CAS requires original raw bytes", 400)
        if max(len(before or b""), len(after)) > LIMIT:
            raise PlatformError("Configuration exceeds the existing byte bound", 413)
        parts = relative_parts(relative)
        with self.directory(root, parts[:-1]) as (path, _, identity):
            token = uuid.uuid4().hex
            temporary_name, backup_name = ".pocketworld-write-" + token, ".pocketworld-before-" + token
            prefix = "/".join(parts[:-1])
            temporary_relative = prefix + "/" + temporary_name if prefix else temporary_name
            backup_relative = prefix + "/" + backup_name if prefix else backup_name
            temporary, backup = ntpath.join(path, temporary_name), ntpath.join(path, backup_name)
            target = ntpath.join(path, parts[-1])
            dispatched, handle, temporary_identity = False, None, None
            intent = {"phase": "preparing", "parentIdentity": identity, "target": relative, "temporary": temporary_relative,
                      "backup": backup_relative if before is not None else None, "beforeSha256": hashlib.sha256(before).hexdigest() if before is not None else None,
                      "afterSha256": hashlib.sha256(after).hexdigest(), "recoveryPolicy": "inspect_exact_objects_never_automatic_rollback_or_gc"}
            try:
                current()
                if self.read_bytes(root, relative) != before:
                    raise PlatformError("Original-byte CAS mismatch; no replacement dispatched")
                intent["beforeObject"] = self._replacement_fact(root, relative)
                handle = self.api.open(temporary, create=True, write=True, delete=True)
                try:
                    self.api.write(handle, after)
                    temporary_identity = self.api.identity(self.api.info(handle))
                except BaseException:
                    self.api.disposition(handle)
                    raise
                finally:
                    self.api.k.CloseHandle(handle); handle = None
                intent["temporaryIdentity"] = temporary_identity
                if self.read_bytes(root, relative) != before:
                    raise PlatformError("File changed before replacement; no replacement dispatched")
                if self._replacement_fact(root, temporary_relative).get("fileIdentity") != temporary_identity or before is not None and self._replacement_fact(root, backup_relative).get("state") != "absent":
                    raise PlatformError("Original temporary/backup object changed; no replacement dispatched")
                current()
                intent["phase"] = "dispatch_intent_persisted"
                record_intent(dict(intent))  # Original file/harness receipt, before the only replacement.
                current()  # Intent persistence may reenter native cancellation/revocation.
                dispatched = True
                # ReplaceFile preserves the existing DACL/attributes. Absent
                # targets use non-replacing MoveFileEx, so a newly created file
                # cannot be overwritten after an absent CAS.
                if before is None:
                    self.api.checked(self.api.k.MoveFileExW(temporary, target, 0x8))
                else:
                    self.api.checked(self.api.k.ReplaceFileW(target, temporary, backup, 0, None, None))
                facts = {"target": self._replacement_fact(root, relative), "temporary": self._replacement_fact(root, temporary_relative),
                         "backup": self._replacement_fact(root, backup_relative) if before is not None else {"state": "not_requested"}}
                verified = self.read_bytes(root, relative) == after and facts["target"].get("fileIdentity") == temporary_identity
                # Preserve a concurrent writer's actual displaced bytes. Never
                # automatically restore old bytes over a third, newer edit.
                if before is not None:
                    verified = verified and facts["backup"].get("sha256") == intent["beforeSha256"] and facts["backup"].get("fileIdentity") == intent["beforeObject"].get("fileIdentity")
                intent.update(phase="readback_verified" if verified else "recovery_required", objects=facts)
                return {"status": "completed" if verified else "unknown", "verified": verified,
                        "replacementAttempted": True, "version": bytes_version(after),
                        "consistency": "optimistic_raw_bytes_cas", "parentIdentity": identity, "nativeReplacement": intent,
                        "recoveryMaterialsRetained": before is not None or not verified}
            except (OSError, PlatformError) as failure:
                if not dispatched:
                    raise
                intent.update(phase="recovery_required", win32Error=getattr(failure, "winerror", None),
                              objects={"target": self._replacement_fact(root, relative), "temporary": self._replacement_fact(root, temporary_relative),
                                       "backup": self._replacement_fact(root, backup_relative) if before is not None else {"state": "not_requested"}})
                return {"status": "unknown", "verified": False, "replacementAttempted": True,
                        "consistency": "optimistic_raw_bytes_cas", "nativeReplacement": intent, "recoveryMaterialsRetained": True,
                        "message": "Replacement may have happened; inspect target/temp/backup by original identity, never replay or automatically roll back"}
            finally:
                if not dispatched and temporary_identity is not None:
                    try:
                        self._delete_owned_bytes(path, temporary_name, hashlib.sha256(after).hexdigest(), len(after), lambda: None, temporary_identity)
                    except (OSError, PlatformError):
                        pass  # Keep uncertain material, never remove a substituted name.

    def import_project_copy(self, root, relative, manifest, files, current, *, record_intent):
        """Publish ONE reviewed new directory, never overwrite/merge a live project.

        Original operation stores intent before native rename. Unknown publication retains
        exact materials for inspection and is never repeated under a new name automatically.
        """
        from device_project_packages import MAX_FILE, MAX_TOTAL, manifest_identity
        parts = relative_parts(relative)
        if not isinstance(files, dict) or not files or len(files)>2000 or sum(len(raw) for raw in files.values())>MAX_TOTAL:
            raise PlatformError("Reviewed project copy exceeds its explicit bound")
        directories = set(manifest["directories"])
        for name, raw in files.items():
            relative_parts(name)
            if not isinstance(raw,bytes) or len(raw)>MAX_FILE: raise PlatformError("Project file exceeds explicit bound")
            pieces=name.split("/")
            directories.update("/".join(pieces[:n]) for n in range(1,len(pieces)))
        directories = sorted(directories, key=lambda name:(name.count("/"),name))
        for name in directories: relative_parts(name)
        sid = self.logon_context()["userSid"]
        with self.directory(root, parts[:-1]) as (parent, parent_handle, parent_identity):
            stage_name = ".pocketworld-project-" + uuid.uuid4().hex
            stage = ntpath.join(parent,stage_name)
            prefix = "/".join(parts[:-1])
            stage_relative = prefix+"/"+stage_name if prefix else stage_name
            handle, attempted, identity, created = None, False, None, False
            created_files, created_dirs = [], []
            intent = {"phase":"preparing", "target":relative,"temporary":stage_relative,
                      "manifestDigest":manifest_identity(manifest),"expectedVersion":"absent",
                      "parentIdentity":parent_identity,"recoveryPolicy":"inspect_original_objects_never_automatic_replay"}
            try:
                current()
                self.api.create_private_directory(stage,sid)
                created=True
                handle=self.api.open(stage,directory=True)
                info=self.api.info(handle)
                if not info.attributes & 0x10 or info.attributes & 0x400 or self.api.final(handle).casefold() != ("\\\\?\\"+stage).casefold():
                    raise PlatformError("New project staging directory identity invalid")
                identity=self.api.identity(info); intent["temporaryIdentity"]=identity
                self.api.private_access(handle,sid,require_protected=True)
                # Keep the original root handle open; all descendant opens retain native
                # non-reparse ancestors. No pathlib/open or POSIX assignment fallback.
                for name in directories:
                    current()
                    pieces=relative_parts(name)
                    with self.directory(stage,pieces[:-1]) as (directory,_,_):
                        self.api.create_private_directory(ntpath.join(directory,pieces[-1]),sid)
                    with self.directory(stage,pieces) as (_,_,bound): created_dirs.append((name,bound))
                for name, raw in files.items():
                    current()
                    self.create_private_snapshot(stage,name,raw,maximum=MAX_FILE,current=current)
                    created_files.append(name)
                self._verify_project_copy(stage,manifest,files,current)
                current(); intent["phase"]="dispatch_intent_persisted"; record_intent(dict(intent)); current()
                self.api.k.CloseHandle(handle); handle=None
                handle=self.api.open(stage,directory=True,delete=True)
                info=self.api.info(handle)
                if self.api.identity(info)!=identity or info.attributes & 0x400:
                    raise PlatformError("Original staging directory was substituted before publish")
                current()
                attempted=True
                self.api.publish_handle(handle,parent_handle,parts[-1])  # Absent-only original directory handle.
                target=ntpath.join(parent,parts[-1])
                if self.api.identity(self.api.info(handle))!=identity or self.api.final(handle).casefold()!=("\\\\?\\"+target).casefold():
                    raise PlatformError("Published project handle readback differs")
                self.api.k.CloseHandle(handle); handle=None
                with self.directory(target) as (_,_,bound):
                    if bound!=identity: raise PlatformError("Published project path changed identity")
                    self._verify_project_copy(target,manifest,files,current)
                intent["phase"]="readback_verified"
                return {"status":"completed","verified":True,"projectImported":True,"delivered":True,
                        "path":relative,"manifestDigest":intent["manifestDigest"],"directoryIdentity":identity,
                        "publicationAttempted":True,"nativeProjectImport":intent,"executionStarted":False,
                        "restoresHistory":False,"restoresEnvironment":False,"restoresExecutionAuthority":False}
            except BaseException as failure:
                if attempted:
                    intent["phase"]="recovery_required"
                    return {"status":"unknown","verified":False,"delivered":False,"publicationAttempted":True,
                            "nativeProjectImport":intent,"recoveryMaterialsRetained":True,
                            "message":"Project publication unconfirmed; inspect original source/target identities without replay"}
                # Only known owned bytes/directories are removed. Any substitution, extra
                # file or failed identity check retains material; never recursive rmtree.
                cleanup = not created or identity is not None
                if identity is not None:
                    for name in reversed(created_files):
                        try:
                            raw=files[name]
                            self.delete_private_snapshot(stage,name,hashlib.sha256(raw).hexdigest(),len(raw))
                        except Exception: cleanup=False
                    for name,bound in reversed(created_dirs):
                        try:
                            pieces=relative_parts(name)
                            with self.directory(stage,pieces[:-1]) as (directory,_,_):
                                child=self.api.open(ntpath.join(directory,pieces[-1]),directory=True,delete=True)
                                try:
                                    if self.api.identity(self.api.info(child))!=bound: raise PlatformError("Staging child changed")
                                    self.api.disposition(child)
                                finally: self.api.k.CloseHandle(child)
                        except Exception: cleanup=False
                    try:
                        if handle is not None: self.api.k.CloseHandle(handle); handle=None
                        handle=self.api.open(stage,directory=True,delete=True)
                        if self.api.identity(self.api.info(handle))!=identity: raise PlatformError("Staging root changed")
                        self.api.disposition(handle)
                    except Exception: cleanup=False
                intent["phase"]="not_published"
                return {"status":"failed","verified":False,"delivered":False,"publicationAttempted":False,
                        "nativeProjectImport":intent,"cleanupPending":not cleanup,"message":"Project not published: "+str(failure)[:200]}
            finally:
                if handle is not None: self.api.k.CloseHandle(handle)

    def capture_project_copy(self, root, relative, original_identity, manifest, current):
        """Capture only the original reviewed file set; added/missing paths require new review."""
        parts=relative_parts(relative)
        files={}
        with self.directory(root,parts) as (physical,_,identity):
            if identity!=original_identity: raise PlatformError("Original imported project directory changed")
            for row in manifest["files"]:
                current()
                files[row["path"]]=self.read_bytes(physical,row["path"],2*1024*1024)
            if sum(len(raw) for raw in files.values())>16*1024*1024:
                raise PlatformError("Modified selected project exceeds 16MiB; nothing exported",413)
            self._verify_project_copy(physical,manifest,files,current)
        return files

    def _verify_project_copy(self, root, manifest, files, current):
        expected_dirs=set(manifest["directories"])
        for name in files:
            pieces=name.split("/"); expected_dirs.update("/".join(pieces[:n]) for n in range(1,len(pieces)))
        for directory in [""]+sorted(expected_dirs):
            current()
            with self.directory(root,relative_parts(directory,empty=True)) as (physical,_,_):
                prefix=directory+"/" if directory else ""
                expected={name[len(prefix):].split("/")[0] for name in set(files)|expected_dirs if name.startswith(prefix) and name!=directory}
                if set(os.listdir(physical))!=expected: raise PlatformError("Project tree contains missing/extra names")
        for name,raw in files.items():
            current()
            if self.read_bytes(root,name,2*1024*1024)!=raw: raise PlatformError("Project tree bytes differ after native publication")
        current()

    def workspace_files(self, state, database):
        from workspace_files_windows import WindowsWorkspaceFiles
        return WindowsWorkspaceFiles(state, database, self)

    def spawn_command(self, argv, *, cwd, **kwargs):
        if not isinstance(argv, list) or not argv or any(not isinstance(x, str) or "\x00" in x for x in argv):
            raise PlatformError("A bounded argv array is required", 400)
        if kwargs.pop("shell", False):
            raise PlatformError("Shell strings are not supported", 400)
        # A native executable is required; .cmd/.bat need an explicit reviewed
        # command adapter, not Windows' implicit shell interpretation.
        if not ntpath.isabs(argv[0]) or ntpath.splitext(argv[0])[1].casefold() != ".exe":
            raise PlatformError("Native Windows command requires an allowlisted absolute .exe path; shell/batch/association fallback is unsupported", 409)
        executable = ntpath.normpath(argv[0])
        # Pin the actual allowlisted image through CreateProcess, not merely a
        # PATH existence check followed by launch from a writable new cwd.
        with self.directory(cwd), self.directory(ntpath.dirname(executable)):
            image = self.api.open(executable)
            try:
                info = self.api.info(image)
                if info.attributes & (0x10 | 0x400) or self.api.final(image).casefold() != ("\\\\?\\" + executable).casefold():
                    raise PlatformError("Allowlisted executable is not the exact regular non-reparse image", 403)
                return WindowsJobProcess([executable, *argv[1:]], cwd=local_root(os.fspath(cwd)), **kwargs)
            finally:
                self.api.k.CloseHandle(image)

    def terminate(self, process, hard=False):
        if not isinstance(process, WindowsJobProcess):
            return {"terminationRequested": False, "leaderReaped": process.poll() is not None,
                    "processGroupStoppedVerified": False, "message": "Process has no original Job Object; parent exit is not a process-tree proof"}
        return process.stop_tree()


class WindowsHandleReader:
    def __init__(self, api, handle, maximum):
        self.api, self.handle, self.maximum = api, handle, maximum
        self.before = self._snapshot()
        info = api.info(handle)
        self.size = (info.sizeHigh << 32) + info.sizeLow
        self.identity = api.identity(info)
        self.position = 0
        if info.attributes & (0x10 | 0x400) or info.links != 1 or self.size > maximum:
            raise PlatformError("Artifact must be a bounded regular single-link non-reparse file", 403)

    def _snapshot(self):
        info = self.api.info(self.handle)
        return [info.volume, info.indexHigh, info.indexLow, info.sizeHigh, info.sizeLow,
                info.written.dwHighDateTime, info.written.dwLowDateTime, info.links, info.attributes]

    def verify_unchanged(self):
        if self.before != self._snapshot():
            raise PlatformError("Pinned artifact changed while reading; discard this transfer")
        return True

    def seek(self, offset):
        if type(offset) is not int or not 0 <= offset <= self.size:
            raise PlatformError("Artifact chunk offset is outside the pinned version", 400)
        function = self.api.k.SetFilePointerEx
        function.argtypes, function.restype = [self.api.w.HANDLE, ctypes.c_int64, ctypes.POINTER(ctypes.c_int64), self.api.w.DWORD], self.api.w.BOOL
        position = ctypes.c_int64()
        self.api.checked(function(self.handle, offset, ctypes.byref(position), 0))
        self.position = position.value
        if self.position != offset:
            raise PlatformError("Artifact seek result is unconfirmed")
        return self.position

    def read(self, size=262144):
        if type(size) is not int or not 0 <= size <= 262144:
            raise PlatformError("Read one bounded artifact chunk at a time", 400)
        size = min(size, self.size - self.position)
        self.verify_unchanged()
        if not size:
            return b""
        buffer, count = ctypes.create_string_buffer(size), self.api.w.DWORD()
        self.api.checked(self.api.k.ReadFile(self.handle, buffer, size, ctypes.byref(count), None))
        self.position += count.value
        self.verify_unchanged()
        if count.value != size:
            raise PlatformError("Artifact ended before its pinned size")
        return buffer.raw[:count.value]


class WindowsJobProcess:
    """Small Popen-shaped native process, confined before its first instruction.

    Only argv executables and pipe/DEVNULL I/O are supported. All inherited
    handles are explicitly allowlisted. KILL_ON_JOB_CLOSE stops descendants on
    a Companion crash; ordinary termination requires ActiveProcesses == 0.
    """
    def __init__(self, argv, *, cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                 stderr=subprocess.STDOUT, env=None, bufsize=0, job_name=None):
        if os.name != "nt":
            raise PlatformError("Windows Job Objects are unavailable on this host")
        import msvcrt
        from ctypes import wintypes as w
        self.api, self.args, self.returncode = Win32(), argv, None
        self.stdin = self.stdout = self.stderr = None
        if job_name is not None and not re.fullmatch(r"Local\\PocketWorldDesktop-[a-f0-9]{32}-[a-f0-9]{32}", job_name):
            raise PlatformError("Invalid private original Job Object name", 400)
        self.job_name, self._stopped_proof = job_name, None
        self._job = self._process = None
        self._lock = threading.RLock()
        self.start_facts = {"processCreated": False, "parentPipesReady": False,
                            "primaryThreadResumeAttempted": False, "primaryThreadResumeSucceeded": False,
                            "userCodeMayHaveRun": False}
        k = self.api.k
        class Security(ctypes.Structure):
            _fields_ = [("length", w.DWORD), ("descriptor", ctypes.c_void_p), ("inherit", w.BOOL)]
        class Startup(ctypes.Structure):
            _fields_ = [("cb", w.DWORD), ("reserved", w.LPWSTR), ("desktop", w.LPWSTR), ("title", w.LPWSTR),
                        ("x", w.DWORD), ("y", w.DWORD), ("xs", w.DWORD), ("ys", w.DWORD), ("xc", w.DWORD),
                        ("yc", w.DWORD), ("fill", w.DWORD), ("flags", w.DWORD), ("show", w.WORD),
                        ("reserved2", w.WORD), ("reserved3", ctypes.c_void_p),
                        ("input", w.HANDLE), ("output", w.HANDLE), ("error", w.HANDLE)]
        class StartupEx(ctypes.Structure):
            _fields_ = [("startup", Startup), ("attributes", ctypes.c_void_p)]
        class ProcessInfo(ctypes.Structure):
            _fields_ = [("process", w.HANDLE), ("thread", w.HANDLE), ("pid", w.DWORD), ("tid", w.DWORD)]
        class BasicLimits(ctypes.Structure):
            _fields_ = [("processTime", ctypes.c_int64), ("jobTime", ctypes.c_int64), ("flags", w.DWORD),
                        ("minimum", ctypes.c_size_t), ("maximum", ctypes.c_size_t), ("active", w.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]
        class IoCounters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in ("ro", "wo", "oo", "rb", "wb", "ob")]
        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("basic", BasicLimits), ("io", IoCounters), ("processMemory", ctypes.c_size_t),
                        ("jobMemory", ctypes.c_size_t), ("peakProcess", ctypes.c_size_t), ("peakJob", ctypes.c_size_t)]
        signatures = {
            "CreateJobObjectW": ([ctypes.c_void_p, w.LPCWSTR], w.HANDLE),
            "SetInformationJobObject": ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD], w.BOOL),
            "TerminateJobObject": ([w.HANDLE, w.UINT], w.BOOL),
            "QueryInformationJobObject": ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p], w.BOOL),
            "CreatePipe": ([ctypes.POINTER(w.HANDLE), ctypes.POINTER(w.HANDLE), ctypes.POINTER(Security), w.DWORD], w.BOOL),
            "SetHandleInformation": ([w.HANDLE, w.DWORD, w.DWORD], w.BOOL),
            "InitializeProcThreadAttributeList": ([ctypes.c_void_p, w.DWORD, w.DWORD, ctypes.POINTER(ctypes.c_size_t)], w.BOOL),
            "UpdateProcThreadAttribute": ([ctypes.c_void_p, w.DWORD, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_void_p], w.BOOL),
            "DeleteProcThreadAttributeList": ([ctypes.c_void_p], None),
            "CreateProcessW": ([w.LPCWSTR, w.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, w.BOOL, w.DWORD, ctypes.c_void_p, w.LPCWSTR, ctypes.POINTER(StartupEx), ctypes.POINTER(ProcessInfo)], w.BOOL),
            "ResumeThread": ([w.HANDLE], w.DWORD),
            "TerminateProcess": ([w.HANDLE, w.UINT], w.BOOL),
            "WaitForSingleObject": ([w.HANDLE, w.DWORD], w.DWORD),
            "GetExitCodeProcess": ([w.HANDLE, ctypes.POINTER(w.DWORD)], w.BOOL),
        }
        for name, (args, result) in signatures.items():
            getattr(k, name).argtypes, getattr(k, name).restype = args, result
        child_handles, parent_handles = [], []
        attributes = None
        pi = ProcessInfo()
        try:
            self._job = self.api.checked(k.CreateJobObjectW(None, job_name))
            if job_name is not None and ctypes.get_last_error() == 183:
                raise PlatformError("Private original Job Object name already exists; no process started")
            limits = ExtendedLimits()
            limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; no breakaway
            self.api.checked(k.SetInformationJobObject(self._job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
            security = Security(ctypes.sizeof(Security), None, True)
            def pipe(input_pipe):
                read, write = w.HANDLE(), w.HANDLE()
                self.api.checked(k.CreatePipe(ctypes.byref(read), ctypes.byref(write), ctypes.byref(security), 0))
                parent, child = (write.value, read.value) if input_pipe else (read.value, write.value)
                parent_handles.append(parent)
                child_handles.append(child)
                self.api.checked(k.SetHandleInformation(parent, 1, 0))
                return parent, child
            # NUL opened with inheritable SECURITY_ATTRIBUTES below.
            def null_handle(write):
                handle = k.CreateFileW("NUL", 0x40000000 if write else 0x80000000, 3, ctypes.byref(security), 3, 0x80, None)
                if handle == ctypes.c_void_p(-1).value:
                    raise ctypes.WinError(ctypes.get_last_error())
                child_handles.append(handle)
                return handle
            si = StartupEx()
            si.startup.cb, si.startup.flags = ctypes.sizeof(si), 0x100
            if stdin == subprocess.PIPE:
                in_parent, si.startup.input = pipe(True)
            elif stdin == subprocess.DEVNULL:
                si.startup.input = null_handle(False)
            else:
                raise PlatformError("Native command stdin must be PIPE or DEVNULL", 400)
            if stdout == subprocess.PIPE:
                out_parent, si.startup.output = pipe(False)
            elif stdout == subprocess.DEVNULL:
                si.startup.output = null_handle(True)
            else:
                raise PlatformError("Native command stdout must be PIPE or DEVNULL", 400)
            if stderr == subprocess.STDOUT:
                si.startup.error = si.startup.output
            elif stderr == subprocess.PIPE:
                err_parent, si.startup.error = pipe(False)
            elif stderr == subprocess.DEVNULL:
                si.startup.error = null_handle(True)
            else:
                raise PlatformError("Native command stderr must be PIPE, STDOUT or DEVNULL", 400)
            size = ctypes.c_size_t()
            k.InitializeProcThreadAttributeList(None, 2, 0, ctypes.byref(size))
            attributes = ctypes.create_string_buffer(size.value)
            self.api.checked(k.InitializeProcThreadAttributeList(attributes, 2, 0, ctypes.byref(size)))
            allowed = (w.HANDLE * len(child_handles))(*child_handles)
            self.api.checked(k.UpdateProcThreadAttribute(attributes, 0, 0x20002, allowed, ctypes.sizeof(allowed), None, None))
            jobs = (w.HANDLE * 1)(self._job)
            # PROC_THREAD_ATTRIBUTE_JOB_LIST. Assignment occurs INSIDE kernel
            # process creation, eliminating the suspended-but-unassigned orphan
            # window on host crash. The non-inheritable Job handle is NOT in
            # HANDLE_LIST. Both arrays live through attribute-list destruction.
            self.api.checked(k.UpdateProcThreadAttribute(attributes, 0, 0x2000D, jobs, ctypes.sizeof(jobs), None, None))
            si.attributes = ctypes.cast(attributes, ctypes.c_void_p)
            environment = None
            if env is not None:
                if any(not isinstance(key, str) or not isinstance(value, str) or "\x00" in key + value or "=" in key for key, value in env.items()):
                    raise PlatformError("Invalid process environment", 400)
                environment = ctypes.create_unicode_buffer("\x00".join(key + "=" + value for key, value in sorted(env.items(), key=lambda x: x[0].casefold())) + "\x00\x00")
            command = ctypes.create_unicode_buffer(subprocess.list2cmdline(argv))
            # CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT
            self.api.checked(k.CreateProcessW(argv[0], command, None, None, True, 0x4 | 0x400 | 0x80000,
                                             environment, cwd, ctypes.byref(si), ctypes.byref(pi)))
            self._process, self.pid = pi.process, pi.pid
            self.start_facts["processCreated"] = True
            # Finish EVERY fallible parent pipe conversion while the original
            # primary thread is still suspended. In particular fdopen can fail;
            # that must not happen after the executable has already run.
            for name, variable, mode in (("stdin", "in_parent", "wb"), ("stdout", "out_parent", "rb"), ("stderr", "err_parent", "rb")):
                if variable in locals():
                    handle = locals()[variable]
                    fd = msvcrt.open_osfhandle(handle, os.O_BINARY | (os.O_WRONLY if mode == "wb" else os.O_RDONLY))
                    parent_handles.remove(handle)
                    try:
                        stream = os.fdopen(fd, mode, buffering=bufsize)
                    except BaseException:
                        # open_osfhandle transferred ownership to this CRT fd;
                        # do not leak it OR close the raw handle a second time.
                        os.close(fd)
                        raise
                    setattr(self, name, stream)
            self.start_facts["parentPipesReady"] = True
            self.start_facts.update(primaryThreadResumeAttempted=True, userCodeMayHaveRun=True)
            previous_suspend_count = k.ResumeThread(pi.thread)
            if previous_suspend_count == 0xFFFFFFFF:
                raise ctypes.WinError(ctypes.get_last_error())
            self.start_facts["primaryThreadResumeSucceeded"] = True
            if previous_suspend_count != 1:
                raise PlatformError("Original primary-thread suspension state was not confirmed")
        except BaseException as error:
            if pi.process:
                self._process, self.pid = pi.process, pi.pid
                self.start_facts["processCreated"] = True
                try:
                    proof = self.stop_tree()
                except BaseException:
                    # Failure of cleanup itself must not erase successful OS
                    # creation and escape as an apparent pre-creation failure.
                    proof = {"terminationRequested": True, "processGroupStoppedVerified": False,
                             "verificationMethod": "original_job_object_active_processes_zero",
                             "message": "Original created-process cleanup failed; retain handles and reconcile locally"}
                if proof.get("processGroupStoppedVerified"):
                    try:
                        self.close()
                    except (OSError, ValueError):
                        proof["handleCloseUnconfirmed"] = True
                # Even a successfully stopped suspended process WAS created.
                # Preserve that fact and the original Job proof through the
                # caller's failed RHS assignment. Unverified handles stay owned.
                raise NativeProcessStartError(self, self.start_facts, proof) from error
            self.close()
            raise
        finally:
            if pi.thread:
                k.CloseHandle(pi.thread)
            if attributes is not None:
                k.DeleteProcThreadAttributeList(attributes)
            for handle in child_handles + parent_handles:
                k.CloseHandle(handle)

    def poll(self):
        with self._lock:
            if self.returncode is not None:
                return self.returncode
            if self.api.k.WaitForSingleObject(self._process, 0) == 0:
                code = self.api.w.DWORD()
                self.api.checked(self.api.k.GetExitCodeProcess(self._process, ctypes.byref(code)))
                self.returncode = code.value
            return self.returncode

    def wait(self, timeout=None):
        if self.returncode is not None:
            return self.returncode
        result = self.api.k.WaitForSingleObject(self._process, 0xFFFFFFFF if timeout is None else max(0, int(timeout * 1000)))
        if result == 0x102:
            raise subprocess.TimeoutExpired(self.args, timeout)
        if result != 0:
            raise PlatformError("Cannot obtain native process termination evidence")
        return self.poll()

    def kill(self):
        with self._lock:
            self.api.checked(self.api.k.TerminateJobObject(self._job, 1))

    terminate = kill

    def tree_active_count(self):
        if self._stopped_proof is not None:
            return 0
        class Accounting(ctypes.Structure):
            _fields_ = [(field, ctypes.c_int64) for field in ("user", "kernel", "periodUser", "periodKernel")] + [(field, self.api.w.DWORD) for field in ("pageFaults", "total", "active", "terminated")]
        value = Accounting()
        if not self.api.k.QueryInformationJobObject(self._job, 1, ctypes.byref(value), ctypes.sizeof(value), None):
            return None  # Unknown is not zero/complete; timeout then stop/reconcile.
        return value.active

    def stop_tree(self):
        if self._stopped_proof is not None:
            return dict(self._stopped_proof)
        proof = {"terminationRequested": True, "processGroupKillAttempted": True,
                 "leaderReaped": False, "processGroupStoppedVerified": False,
                 "verificationMethod": "original_job_object_active_processes_zero"}
        with self._lock:
            try:
                self.kill()
                self.wait(timeout=5)
                proof["leaderReaped"] = True
                # JOBOBJECT_BASIC_ACCOUNTING_INFORMATION has six 64-bit counters
                # followed by four DWORD counters (total, active, terminated).
                class Accounting(ctypes.Structure):
                    _fields_ = [(name, ctypes.c_int64) for name in ("user", "kernel", "periodUser", "periodKernel")] + [(name, self.api.w.DWORD) for name in ("pageFaults", "total", "active", "terminated")]
                deadline = time.monotonic() + 5
                while True:
                    accounting = Accounting()
                    self.api.checked(self.api.k.QueryInformationJobObject(self._job, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None))
                    proof["activeProcesses"] = accounting.active
                    if accounting.active == 0:
                        proof["processGroupStoppedVerified"] = True
                        break
                    if time.monotonic() >= deadline:
                        break
                    time.sleep(0.02)
            except (OSError, PlatformError, subprocess.TimeoutExpired):
                proof["message"] = "Job termination remains unconfirmed; retain the original resource lease"
            if proof["processGroupStoppedVerified"]:
                self._stopped_proof = dict(proof)
        return proof

    def close(self):
        for stream in (self.stdin, self.stdout, self.stderr):
            if stream is not None:
                stream.close()
        for name in ("_job", "_process"):
            handle = getattr(self, name, None)
            if handle:
                self.api.k.CloseHandle(handle)
                setattr(self, name, None)
