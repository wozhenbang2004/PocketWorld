"""Private, bounded original Windows workspace versions; no pathname-based cache deletion.

Only bytes already read/written by the original workspace API are retained. SQLite
holds the binding, not a second job/permission or another copy of the file body.
Old pathlib caches have no native binding: never import, enumerate or remove them.
"""
from contextlib import contextmanager
import hashlib
import json
import re
import sqlite3
import threading
import time

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, relative_parts

MAX_BYTES,PER_PATH,PER_VAULT=262144,8,256  # 64 MiB original bytes, plus at most one in-flight entry
TABLE="windows_file_revision_v1"
_JUST_CREATED=object()


def _bad(): raise PlatformError("Original Windows file revision unavailable; never substitute another cache or path")


def _digest(raw): return hashlib.sha256(raw).hexdigest()


def _json(value): return json.dumps(value,ensure_ascii=False,separators=(",",":")).encode()


def _identity(api,handle):
    info=api.info(handle)
    index=api.identity(info); born=[info.created.dwHighDateTime,info.created.dwLowDateTime]
    if not any(index[1:]) or not any(born): _bad()
    return [*index,*born]


def _origin(value):
    return isinstance(value,list) and len(value)==5 and all(type(v) is int and 0<=v<2**32 for v in value) and any(value[1:3]) and any(value[3:])


def _hash(value): return isinstance(value,str) and re.fullmatch(r"[a-f0-9]{64}",value) is not None


def _name(scope,sha): return "retained-version-v1-"+_digest(_json([scope,sha]))+".bin"


class NativeFileVersions:
    def __init__(self,state,db,backend):
        self.root,self.db,self.backend=str(state),db,backend
        self.lock=threading.RLock()
        self.ready=False  # Optional revision storage never blocks ordinary Companion startup.

    def _schema(self,create):
        if self.ready: return
        if not self.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(TABLE,)).fetchone():
            if not create: _bad()  # a missing historical version is not a reason to write anything
            self._change(f"""CREATE TABLE IF NOT EXISTS {TABLE}(
                         vault TEXT NOT NULL,scope TEXT NOT NULL,digest TEXT NOT NULL,size INTEGER NOT NULL,
                         used INTEGER NOT NULL,origin TEXT,PRIMARY KEY(vault,scope,digest))""",())
        self.ready=True

    @contextmanager
    def _scope(self,env,path,*,create=False):
        relative_parts(path)
        if env.get("kind","local")!="local": _bad()
        sid=self.backend.logon_context()["userSid"]
        with self.lock,self.backend.directory(self.root) as (_,parent,_),self.backend.directory(env["root"]) as (_,project,_):
            self.backend.api.private_access(parent,sid,require_protected=False)
            vault=_digest(_json(["pw-local-revisions-v1",sid,_identity(self.backend.api,parent)]))
            scope=_digest(_json([vault,_identity(self.backend.api,project),path]))
            self._schema(create)
            yield vault,scope,sid

    def _change(self,statement,args):
        # Independent short cache metadata update; never take over/commit somebody's
        # open business transaction. Changed body files are absent-only originals.
        if self.db.in_transaction: _bad()
        self.db.execute("SAVEPOINT pw_native_file_revision")
        try:
            changed=self.db.execute(statement,args).rowcount
            self.db.execute("RELEASE SAVEPOINT pw_native_file_revision")
            return changed
        except BaseException:
            self.db.execute("ROLLBACK TO SAVEPOINT pw_native_file_revision")
            self.db.execute("RELEASE SAVEPOINT pw_native_file_revision")
            raise

    def _row(self,vault,scope,sha):
        return self.db.execute(f"SELECT size,origin FROM {TABLE} WHERE vault=? AND scope=? AND digest=?",(vault,scope,sha)).fetchone()

    def _bytes(self,scope,sha,size,ids,sid):
        if not _hash(scope) or not _hash(sha) or type(size) is not int or not 0<=size<=MAX_BYTES: _bad()
        with self.backend._private_leaf(self.root,_name(scope,sha),sid,optional=False) as handle:
            birth=_identity(self.backend.api,handle)
            if ids is not _JUST_CREATED and (not _origin(ids) or ids!=birth): _bad()
            reader=WindowsHandleReader(self.backend.api,handle,MAX_BYTES)
            if reader.size!=size: _bad()
            reader.seek(0); raw=reader.read(size)
            if not reader.verify_unchanged() or _digest(raw)!=sha: _bad()
            return raw,birth

    def _trim(self,vault,scope,keep,sid,global_limit=False):
        clause,args=("vault=?",(vault,)) if global_limit else ("vault=? AND scope=?",(vault,scope))
        count=self.db.execute(f"SELECT COUNT(*) FROM {TABLE} WHERE {clause}",args).fetchone()[0]
        if count<=(PER_VAULT if global_limit else PER_PATH): return
        rows=self.db.execute(f"""SELECT scope,digest,size,origin FROM {TABLE} WHERE {clause}
                        AND NOT(scope=? AND digest=?) AND origin IS NOT NULL ORDER BY used,digest LIMIT 8""",(*args,scope,keep)).fetchall()
        for s,sha,size,fact in rows:
            try:
                if not _hash(s) or not _hash(sha) or type(size) is not int or not 0<=size<=MAX_BYTES: continue
                ids=json.loads(fact)
                if not _origin(ids): continue
                # Keep the replacement-safe deletion authority on the original private
                # cache file only; never on a user's workspace/source/legacy revision.
                def current():
                    if self._row(vault,s,sha)!=(size,fact): _bad()
                receipt=self.backend._delete_owned_bytes(self.root,_name(s,sha),sha,size,current,ids[:3],
                                                        expected_creation=ids[3:],private_sid=sid)
                if receipt.get("verified") is not True or receipt.get("deleted") is not True:
                    if receipt.get("status")=="unknown":
                        self._change(f"UPDATE {TABLE} SET origin=? WHERE vault=? AND scope=? AND digest=? AND origin=?",
                                     ('{"held":"unconfirmed_retirement"}',vault,s,sha,fact))
                    continue
                self._change(f"DELETE FROM {TABLE} WHERE vault=? AND scope=? AND digest=? AND origin=?",(vault,s,sha,fact))
                return
            except (OSError,ValueError,PlatformError,sqlite3.Error): continue  # Unknown/busy objects stay untouched.

    def remember(self,env,path,raw):
        if not isinstance(raw,bytes) or len(raw)>MAX_BYTES: _bad()
        sha=_digest(raw); size=len(raw)
        with self._scope(env,path,create=True) as (vault,scope,sid):
            row=self._row(vault,scope,sha)
            if row is not None:
                if row[0]!=size or row[1] is None: _bad()
                body,_=self._bytes(scope,sha,size,json.loads(row[1]),sid)
                if body!=raw: _bad()
                if self._change(f"UPDATE {TABLE} SET used=? WHERE vault=? AND scope=? AND digest=? AND origin=?",
                                (time.time_ns(),vault,scope,sha,row[1]))!=1: _bad()
                return "sha256:"+sha
            # An ambiguous previous eviction/reservation blocks growth beyond the
            # one extra slot. Never adopt a preexisting file by its digest/name.
            self._trim(vault,scope,sha,sid)
            self._trim(vault,scope,sha,sid,True)
            added=self._change(f"""INSERT INTO {TABLE}(vault,scope,digest,size,used)
                SELECT ?,?,?,?,? WHERE (SELECT COUNT(*) FROM {TABLE} WHERE vault=? AND scope=?)<=?
                AND (SELECT COUNT(*) FROM {TABLE} WHERE vault=?)<=?""",
                (vault,scope,sha,size,time.time_ns(),vault,scope,PER_PATH,vault,PER_VAULT))
            if added!=1: _bad()
            def current():
                if self._row(vault,scope,sha)!=(size,None): _bad()
            receipt=self.backend.create_private_snapshot(self.root,_name(scope,sha),raw,maximum=MAX_BYTES,current=current)
            body,ids=self._bytes(scope,sha,size,_JUST_CREATED,sid)
            if (body!=raw or receipt.get("fileIdentity")!=ids[:3] or receipt.get("bytes")!=size or receipt.get("sha256")!=sha): _bad()
            if self._change(f"UPDATE {TABLE} SET origin=? WHERE vault=? AND scope=? AND digest=? AND origin IS NULL",
                            (json.dumps(ids),vault,scope,sha))!=1: _bad()
            self._trim(vault,scope,sha,sid)
            self._trim(vault,scope,sha,sid,True)
            return "sha256:"+sha

    def read(self,env,path,version):
        if not isinstance(version,str) or not re.fullmatch(r"sha256:[a-f0-9]{64}",version): _bad()
        sha=version[7:]
        with self._scope(env,path) as (vault,scope,sid):
            row=self._row(vault,scope,sha)
            if not row or row[1] is None: _bad()
            body,_=self._bytes(scope,sha,row[0],json.loads(row[1]),sid)
            return body
