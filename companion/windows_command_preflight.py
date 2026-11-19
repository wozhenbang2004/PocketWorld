"""Installed owner's local inspection of ONE configured command. No network, PATH search, binary read or spawn."""
from contextlib import ExitStack,contextmanager
from dataclasses import dataclass
import hashlib
import ntpath
import re
import unicodedata

from platform_backend import PlatformError
from platform_windows import local_root
from windows_git_source import registered
from windows_local_read_handle import open_fixed
from windows_installation import _object
from windows_source_publisher import _origin

ALIAS=re.compile(r"[A-Za-z][A-Za-z0-9_-]{0,47}")


def bad(): raise PlatformError("Original owner's command selection cannot be verified")


def _version(info):
    return (info.sizeHigh<<32|info.sizeLow,info.written.dwHighDateTime,info.written.dwLowDateTime,info.attributes)


def safe_display(value):
    return isinstance(value,str) and not any(unicodedata.category(c) in ("Cc","Cf","Cs","Zl","Zp") for c in value)


def catalog(ctx,settings):
    # Reuse the installed local-workspace boundary; SSH and folders overlapping this installation are not command evidence.
    roots=dict(registered(ctx,settings))
    data=ctx._raw(ctx.state,"companion.json",262144)
    if not isinstance(data,bytes) or hashlib.sha256(data).hexdigest()!=settings: bad()
    envs=_object(data)["environments"]
    return {item["id"]:(roots[item["id"]],item) for item in envs if item["id"] in roots and safe_display(roots[item["id"]])}


def inventory(env):
    workers=env.get("workers",["command"]); mapping=env.get("commands",{})
    if not isinstance(workers,list) or any(not isinstance(v,str) for v in workers): return "invalid",(),0
    if "command" not in workers: return "not_enabled",(),0
    if not isinstance(mapping,dict): return "invalid",(),0
    if len(mapping)>128: return "over_limit",(),len(mapping)
    if any(not isinstance(p,str) for p in mapping.values()): return "invalid",(),0
    if not mapping: return "empty",(),0
    names=tuple(sorted(n for n in mapping if isinstance(n,str) and ALIAS.fullmatch(n)))
    return ("registered" if names else "no_short_names"),names,len(mapping)-len(names)


@dataclass(frozen=True)
class Selection:
    environment: str
    root: str
    name: str
    program: str|None
    kind: str


def select(ctx,settings,eid,name):
    result=catalog(ctx,settings)
    if eid not in result: bad()
    root,env=result[eid]
    state,names,_=inventory(env)
    if state!="registered" or name not in names: bad()
    command=env["commands"][name]
    # Bare names depend on the actual service's PATH/current directory. Do not resolve there, search drives,
    # interpret environment variables, parse arguments, or follow network/reparse aliases even on explicit recheck.
    if not command or not safe_display(command): kind="unsupported"
    elif not re.match(r"^[A-Za-z]:[\\/]",command):
        kind="needs_path" if re.fullmatch(r"[A-Za-z0-9_-]+(?:\.exe)?",command,re.I) else "unsupported"
    else:
        try: path=local_root(command)
        except (TypeError,ValueError,PlatformError): path=None
        if not path or path.casefold()!=command.replace("/","\\").casefold(): kind="unsupported"
        elif ntpath.splitext(path)[1].casefold()!=".exe": kind="not_exe"
        elif (ntpath.splitdrive(path)[0].casefold()==ntpath.splitdrive(ctx.home)[0].casefold()
                and ntpath.commonpath([ctx.home,path]).casefold()==ctx.home.casefold()): kind="unsupported"
        else: return Selection(eid,root,name,path,"absolute")
    return Selection(eid,root,name,None,kind)


@contextmanager
def _handle(ctx,path,*,directory,check):
    api=ctx.backend.api; path=local_root(path); parts=path[3:].split("\\") if path[3:] else []
    if len(parts)>32 or not directory and not parts: bad()
    with ExitStack() as pins:
        # Existing backend rejects non-fixed drives and pins the actual volume root, not System32's working directory.
        root,handle,_=pins.enter_context(ctx.backend.directory(path[:3]))
        info=api.info(handle)
        if info.attributes&(0x400|0x1000|0x40000|0x400000) or not info.attributes&0x10: bad()
        birth=tuple(_origin(api,info)); dest=root
        for i,piece in enumerate(parts):
            check(); dest=ntpath.join(dest,piece)
            handle,info,birth=open_fixed(api,dest,directory or i+1<len(parts),pins)  # FILE_READ_ATTRIBUTES, OPEN_REPARSE_POINT, NO_RECALL
        check()
        yield handle,birth,_version(info)


def inspect(ctx,settings,chosen,check):
    check()
    if select(ctx,settings,chosen.environment,chosen.name)!=chosen: bad()
    with ExitStack() as pinned:
        try: root,root_id,_=pinned.enter_context(_handle(ctx,chosen.root,directory=True,check=check))
        except (OSError,ValueError,PlatformError): return "workspace_unknown",None
        program=None
        if chosen.kind=="absolute":
            try: program=pinned.enter_context(_handle(ctx,chosen.program,directory=False,check=check))
            except (OSError,ValueError,PlatformError): pass
        check()
        if (select(ctx,settings,chosen.environment,chosen.name)!=chosen
                or tuple(_origin(ctx.backend.api,ctx.backend.api.info(root)))!=root_id): bad()
        if chosen.kind!="absolute": return chosen.kind,None
        if program is None: return "program_unknown",None
        handle,birth,before=program; now=ctx.backend.api.info(handle)
        if tuple(_origin(ctx.backend.api,now))!=birth or _version(now)!=before: return "program_unknown",None
        return "file_observed",before[0]
