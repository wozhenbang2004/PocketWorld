"""Compare ONE explicitly selected, Git-tracked local worktree text. No status scan, index, shell or output."""
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import difflib
import hashlib
import ntpath

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader
from windows_git_one_file import MAX_TEXT, node, pieces
from windows_git_source import _object_body
from windows_git_subproject import parent as checked_project_root
from windows_git_tree import FILE
from windows_source_diff import _lines
from windows_source_publisher import _origin
from windows_local_read_handle import EXCLUDED, attributes as _attributes, open_fixed as _open

def bad(): raise PlatformError("Cannot bind this local single-file worktree comparison")


@dataclass(frozen=True)
class Selection:
    path: str
    source: tuple
    git: tuple
    chain: tuple
    file: tuple
    version: tuple


def _version(info):
    return ((info.sizeHigh<<32)+info.sizeLow,info.written.dwHighDateTime,info.written.dwLowDateTime,info.attributes)


@contextmanager
def selected(ctx,settings,source,committed,check):
    check()
    if not committed.repository or not committed.git or committed.mode!="100644" or committed.size>MAX_TEXT: bad()
    parts=pieces(committed.path)
    with node(ctx,settings,source,committed.path,check) as (pins,packs,mode,oid), ExitStack() as stack:
        if mode!=FILE or oid!=committed.oid: bad()
        api=pins.api; root,root_handle,_,_=pins.nodes[()]; git=pins.nodes[(".git",)][1]
        if (tuple(checked_project_root(ctx,root,stack))!=committed.repository
                or tuple(_origin(api,api.info(root_handle)))!=committed.repository
                or tuple(_origin(api,api.info(git)))!=committed.git or api.info(root_handle).attributes&EXCLUDED): bad()
        chain=[committed.repository]; markers=[]; path=root
        def not_nested():
            for marker in markers:
                check()
                if _attributes(api,marker,absent=True) is not None: bad()
        for piece in parts[:-1]:
            check(); path=ntpath.join(path,piece)
            _,_,birth=_open(api,path,True,stack); chain.append(birth)
            marker=ntpath.join(path,".git")
            if _attributes(api,marker,absent=True) is not None: bad()
            markers.append(marker)
        path=ntpath.join(path,parts[-1])
        _,info,birth=_open(api,path,False,stack); version=_version(info)
        if version[0]>MAX_TEXT: bad()
        original=(source["environmentId"],source["repositoryAnchor"],source["commit"],source["tree"],source["objectFormat"],
                  committed.path,committed.oid,committed.size,committed.sha)
        live=Selection(path,original,committed.git,tuple(chain),birth,version)
        check()
        yield live,pins,packs,oid,stack
        not_nested(); check()


def plan(ctx,settings,source,committed,check):
    with selected(ctx,settings,source,committed,check) as (live,*_): return live


def _content(raw):
    if _lines(raw) is None: bad()
    text=raw.decode("utf-8-sig")
    if text.startswith("version https://git-lfs.github.com/spec/v1") or "\u2028" in text or "\u2029" in text: bad()
    crlf=text.count("\r\n"); lf=text.count("\n")-crlf; cr=text.count("\r")-crlf
    style=" / ".join(s for n,s in ((crlf,"CRLF"),(lf,"LF"),(cr,"CR")) if n) or "无换行"
    fmt=("UTF-8 BOM" if raw.startswith(b"\xef\xbb\xbf") else "UTF-8 无 BOM")+" · "+style
    return text.replace("\r\n","\n").replace("\r","\n"),fmt


def _body(live,pins,stack):
    h,info,birth=_open(pins.api,live.path,False,stack,body=True)
    if birth!=live.file or _version(info)!=live.version: bad()
    reader=WindowsHandleReader(pins.api,h,MAX_TEXT)
    reader.seek(0); data=reader.read(MAX_TEXT)
    if not reader.verify_unchanged() or len(data)!=live.version[0]: bad()
    return reader,data


@contextmanager
def same_observation(ctx,settings,source,committed,reviewed,sha,check):
    """Hold the originally observed physical working file/version. No search or replacement."""
    with selected(ctx,settings,source,committed,check) as (live,pins,_,_,stack):
        if live!=reviewed: bad()
        reader,data=_body(live,pins,stack)
        if hashlib.sha256(data).hexdigest()!=sha: bad()
        check(); yield data
        if not reader.verify_unchanged(): bad()


def compare(ctx,settings,source,committed,reviewed,check):
    with selected(ctx,settings,source,committed,check) as (live,pins,packs,oid,stack):
        if live!=reviewed: bad()
        before,_=_object_body(pins,packs,oid,b"blob",MAX_TEXT)
        if len(before)!=committed.size or hashlib.sha256(before).hexdigest()!=committed.sha: bad()
        check()
        reader,data=_body(live,pins,stack)
        old,a=_content(before); new,b=_content(data); check()
        same=before==data
        # Exact raw hashes remain authoritative. The text diff normalizes BOM/newlines for reading;
        # split on LF (including the final empty line), so missing last newline is not silently lost.
        diff="\r\n".join(difflib.unified_diff(old.split("\n"),new.split("\n"),fromfile="原提交（文本）",
                            tofile="已点选的工作文件（文本）",n=3,lineterm="")) if new!=old else None
        if diff is not None and len(diff.encode("utf-16le"))//2>262144: bad()  # don't call a clipped diff complete
        if not reader.verify_unchanged(): bad()
        return {"same":same,"diff":diff,"oldFormat":a,"newFormat":b,"currentSha":hashlib.sha256(data).hexdigest(),
                "current":new.replace("\n","\r\n")}
