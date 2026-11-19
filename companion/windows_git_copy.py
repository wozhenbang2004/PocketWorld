"""Explicit local source-file copies. Not a Git checkout, phone job or worker.

The fixed bank is an append-only artifact receipt, not another task scheduler.
An interrupted/ambiguous entry is inspected, never replayed or garbage-collected.
"""
from contextlib import ExitStack
import hashlib
import json
import ntpath
import re
import time

from assignment_projects import manifest_identity
from platform_backend import PlatformError
from windows_git_contents import ContentProblem, MAX_FILES, MAX_FILE, MAX_TOTAL
from windows_git_source import capture, registered
from windows_git_subproject import valid as valid_selected_project
from windows_installation import _object
from windows_source_publisher import disk, publish
import windows_source_inventory as inventory
import windows_git_one_copy as one_copy

PROFILE = "pocketworld.windows-local-source-file-copy.v1"
BANK = 8
FILE_BANK = 8
SOURCE_KEYS = ("environmentId","repositoryAnchor","objectFormat","commit","tree","branch")


class CopyBlocked(Exception):
    def __init__(self, reason): self.reason = reason


def storage_limit(review):
    if review.get("lfsHeaderFiles"): return "含 LFS 指针；此入口不将指针包装成完整外部资源。"
    if not review.get("files"): return "这次提交没有可通过此入口保存的普通文件。"
    if (review["files"]>MAX_FILES or review["logicalBytes"]>MAX_TOTAL
            or review.get("largestFileBytes",MAX_FILE+1)>MAX_FILE):
        return "超过本机源码副本上限（8,192 个文件、单件 16 MiB、合计 128 MiB）；不写部分副本。"
    return None


def _json(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(",",":"),allow_nan=False).encode("utf-8")


def _key(install,source):
    return hashlib.sha256(_json([PROFILE,install,source])).hexdigest()[:32]


def _target(op): return "source-"+op


def _slot(n):
    return ("source-one-file-v1-slot-"+str(n-BANK) if n>=BANK else "source-copy-v1-slot-"+str(n))+".json"


def bank(one): return range(BANK,BANK+FILE_BANK) if one else range(BANK)


def _phase(op,phase): return "source-copy-v1-"+op+"-"+phase+".json"


def _read(ctx,name,limit):
    raw = ctx.backend.read_optional_private_bytes(ctx.state,name,ctx.sid,maximum=limit)
    return None if raw is None else _object(raw)


def _store(ctx,name,value,limit,current):
    return ctx.backend.create_private_snapshot(ctx.state,name,_json(value),maximum=limit,current=current)


def _native_id(value):
    return isinstance(value,list) and len(value)==3 and all(type(v) is int and 0<=v<2**32 for v in value)


class Material:
    def __init__(self): self.names,self.directories,self.rows,self.size = set(),[],[],0

    def keep(self,path,mode,oid,raw,sha):
        self.size += len(raw)
        if (len(self.names)>=MAX_FILES or self.size>MAX_TOTAL or len(raw)>MAX_FILE
                or path in self.names): raise ContentProblem("content_limit")
        self.names.add(path)  # only metadata survives this first source pass
        self.rows.append({"path":path,"mode":mode,"object":oid,"bytes":len(raw),"sha256":sha})


def source_facts(source):
    if "selectedFile" in source:
        if source.get("sourceVerified") is not True: raise PlatformError("Original committed file is required")
        selected={"selectedProject":source["selectedProject"]} if "selectedProject" in source else {}
        return {**{key:source[key] for key in SOURCE_KEYS},**selected,**one_copy.facts(source)}
    review,contents = source.get("committedNameReview",{}),source.get("committedContentReview",{})
    if not (source.get("sourceVerified") is True and review.get("complete") is True and contents.get("complete") is True):
        raise PlatformError("Original complete local source evidence is required")
    selected={"selectedProject":source["selectedProject"]} if "selectedProject" in source else {}
    return {**{key:source[key] for key in SOURCE_KEYS},**selected,"mapping":review["mappingDigest"],
            "content":contents["contentManifestDigest"],"directories":review["directories"],"files":contents["files"],
            "bytes":contents["logicalBytes"],"largest":contents["largestFileBytes"],"lfsHeaders":contents["lfsHeaderFiles"]}


def plan(ctx,source):
    facts = source_facts(source)
    op = _key(ctx.install_state["installId"],facts)
    return op,ntpath.join(ctx.state,_target(op))


def slots(ctx,*,one=None):
    ctx.check_current_pointer()
    ctx.backend.assert_private_directory(ctx.state,ctx.sid)
    entries = []
    for n in (range(BANK+FILE_BANK) if one is None else bank(one)):
        row = _read(ctx,_slot(n),16384)
        if row is not None:
            facts = row.get("source")
            if (row.get("format")!=PROFILE or row.get("install")!=ctx.install_state["installId"]
                    or not isinstance(facts,dict) or not isinstance(facts.get("environmentId"),str)
                    or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}",facts["environmentId"])
                    or ("selectedFile" in facts)!=(n>=BANK) or (n>=BANK and not one_copy.matches(facts))
                    or "selectedProject" in facts and not valid_selected_project(facts["selectedProject"],row["install"],facts["environmentId"])
                    or not isinstance(facts.get("commit"),str) or not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}",facts["commit"])
                    or facts.get("objectFormat") not in ("sha1","sha256")
                    or any(type(facts.get(k)) is not int or facts[k]<0 for k in ("files","bytes","directories","largest","lfsHeaders"))
                    or facts["files"]>MAX_FILES or facts["bytes"]>MAX_TOTAL or facts["largest"]>MAX_FILE
                    or any(not isinstance(facts.get(k),str) or not re.fullmatch(r"[a-f0-9]{64}",facts[k]) for k in ("repositoryAnchor","mapping","content"))
                    or row.get("operation")!=_key(row["install"],facts) or row.get("target")!=_target(row["operation"])
                    or not isinstance(row.get("manifestDigest"),str) or not re.fullmatch("[a-f0-9]{64}",row["manifestDigest"])
                    or type(row.get("at")) is not int or not 0<=row["at"]<=4102444800000):
                raise PlatformError("Original local copy receipt cannot be identified")
            entries.append((n,row))
    return entries


def _directory_id(ctx,name):
    # Missing/unreadable/reparse/mismatched are all UNCONFIRMED, not permission
    # to create, delete, adopt or replay at this previously recorded location.
    try:
        with ctx.backend.directory(ctx.state,[name]) as (_,handle,identity):
            ctx.backend.api.private_access(handle,ctx.sid,require_protected=True)
            return identity
    except (OSError,PlatformError): return None


def inspect(ctx,row):
    if not any(current==row for _,current in slots(ctx,one="selectedFile" in row["source"])): raise PlatformError("Original reservation no longer matches")
    op, target = row["operation"],_target(row["operation"])
    opened = _read(ctx,_phase(op,"opened"),4096)
    sealed, final = _read(ctx,_phase(op,"stage"),4096),_read(ctx,_phase(op,"result"),4096)
    expected = {"operation":op,"install":ctx.install_state["installId"],"manifestDigest":row["manifestDigest"]}
    if any(any(record.get(k)!=v for k,v in expected.items()) for record in (opened,sealed,final) if record is not None):
        raise PlatformError("Original publication records differ")
    if sealed and (not opened or not _native_id(opened.get("root")) or opened["root"]!=sealed.get("root")):
        raise PlatformError("Original opened/staged directory identities differ")
    if final is not None and (final.get("status") not in ("completed","failed","unknown")
            or final["status"]=="completed" and (not sealed or not _native_id(sealed.get("root")) or final.get("root")!=sealed["root"])):
        raise PlatformError("Original final publication proof incomplete")
    evidence = {"operation":op,"source":row["source"],"at":row["at"],"status":"incomplete"}
    if final and final.get("status")=="failed":
        evidence.update(status="failed",cleanupConfirmed=final.get("cleanupConfirmed") is True)
    if sealed and _native_id(sealed.get("root")):
        target_id = _directory_id(ctx,target)
        if target_id==sealed["root"]:
            evidence.update(status="published_unconfirmed",path=ntpath.join(ctx.state,target))
            if final and final.get("status")=="completed" and final.get("root")==target_id:
                evidence.update(status="saved",savedAt=final.get("at"),itemsRecorded=bool(inventory._claim_ok(final.get("items"))))
        elif final and final.get("status")=="completed":
            evidence["status"]="saved_original_unavailable"
    if (evidence["status"] in ("failed","incomplete") and opened and _native_id(opened.get("root"))
            and _directory_id(ctx,".pocketworld-project-"+op)==opened["root"]):
        evidence["status"]="original_stage_retained"
    return evidence


def save(ctx,settings,source,checkpoint,progress,phase,locked):
    one="selectedFile" in source; records=slots(ctx,one=one)
    original,install = source_facts(source),ctx.install_state["installId"]
    op = _key(install,original)
    previous = next((r for n,r in records if r["operation"]==op),None)
    if previous is not None: return inspect(ctx,previous)
    if len(records)>=len(bank(one)): raise CopyBlocked("bank_full")
    if not one and storage_limit(source["committedContentReview"]): raise CopyBlocked("outside_limit")
    material = Material()
    phase("正在重新核对同一提交的源文件")
    def rebuild(sink,check,notice):
        if one: return one_copy.materialize(ctx,settings,source,sink,check,notice)
        checked=capture(ctx,settings,source["environmentId"],layout_for=source,content=True,
                        checkpoint=check,on_count=notice,material=sink)
        if not checked.get("committedContentReview",{}).get("complete") or source_facts(checked)!=original:
            raise CopyBlocked("source_changed")
    rebuild(material,checkpoint,progress)
    manifest = {"format":PROFILE,"source":original,"directories":material.directories,"files":material.rows}
    digest = manifest_identity(manifest)
    # Bound saved IDs plus their 64-bit creation timestamps before reserving any slot.
    if len(_json(manifest))+64*(len(material.directories)+len(material.rows))+1024>inventory.LIMIT:
        raise CopyBlocked("inventory_limit")
    n = next(n for n in bank(one) if all(used!=n for used,_ in records))
    target = _target(op)
    last_check = 0.
    def current(force=False):
        nonlocal last_check
        checkpoint()  # checked for every object, including owner close/lock and the total time bound
        now = time.monotonic()
        if force or now-last_check>=0.25:
            if locked(): raise PlatformError("Original local interactive owner is not available")
            if source["environmentId"] not in dict(registered(ctx,settings)): raise PlatformError("Original registration differs")
            last_check = now
    def ensure_state():
        ctx.check_current_pointer()
        ctx.backend.assert_private_directory(ctx.state,ctx.sid)
    with ExitStack() as hold, ctx.backend.directory(ctx.state) as (_,state_handle,state_identity):
        ctx.backend.api.private_access(state_handle,ctx.sid,require_protected=True)
        env=original["environmentId"]; root=dict(registered(ctx,settings)).get(env)
        if not root: raise CopyBlocked("source_changed")
        work_origin=None
        try:
            _,work_handle,_=hold.enter_context(ctx.backend.directory(root))
            from windows_source_publisher import _origin as native_origin
            work_origin=native_origin(ctx.backend.api,ctx.backend.api.info(work_handle))
        except (OSError,PlatformError):
            pass  # Optional cross-check witness; never reject/upgrade a legacy direct-root copy just for this field.
        if "selectedProject" in original:
            from windows_git_subproject import source_pins
            if work_origin is not None and original["selectedProject"]["parent"]!=work_origin: raise CopyBlocked("source_changed")
            # No rebinding in the gap between the two source passes or at final publication.
            source_pins(ctx,root,env,original["selectedProject"],hold)
            work_origin=original["selectedProject"]["parent"]
        if one: one_copy.hold_original(ctx,settings,source,hold,checkpoint)
        current(True)
        if not disk(ctx,original)["enough"]: raise CopyBlocked("disk_space")
        for leaf in (target,".pocketworld-project-"+op):
            try: existing = ctx.backend.api.open(ntpath.join(ctx.state,leaf),directory=True)
            except OSError as error:
                if getattr(error,"winerror",None)==2: continue  # exact last component only
                raise
            ctx.backend.api.k.CloseHandle(existing)
            raise CopyBlocked("destination_present")
        # Reserve a single immutable slot absent-only, before touching the
        # staging tree. A collision/failure is never retried under another ID.
        row = {"format":PROFILE,"install":install,"operation":op,"target":target,"source":original,
               "at":int(time.time()*1000),"manifestDigest":digest}
        _store(ctx,_slot(n),row,16384,lambda:current(True))
        phase("正在把已核实的源文件写入独立的私有暂存目录，并核对实际字节")
        evidence = {"operation":op,"install":install,"manifestDigest":digest}
        def record(phase_id,intent):
            if (intent.get("target")!=target or intent.get("temporary")!=".pocketworld-project-"+op
                    or intent.get("manifestDigest")!=digest or intent.get("parentIdentity")!=state_identity
                    or not _native_id(intent.get("temporaryIdentity"))):
                raise PlatformError("Original staging identity not verified")
            if phase_id=="items":
                value = {"format":inventory.PROFILE,"operation":op,"manifest":manifest,"root":intent["temporaryIdentity"],
                         "rootOrigin":intent["rootOrigin"],"directories":intent["directories"],"files":intent["files"]}
                if work_origin is not None: value["registeredRootOrigin"]=work_origin
                return inventory.claim(_store(ctx,_phase(op,"items"),value,inventory.LIMIT,ensure_state))
            if phase_id not in ("opened","stage"): raise PlatformError("Original publication phase unknown")
            _store(ctx,_phase(op,phase_id),{**evidence,"root":intent["temporaryIdentity"]},4096,lambda:current(True))
            if phase_id=="stage": phase("原暂存目录内容已核实，正在以不覆盖的方式发布并回读目标")
        def feed(append):
            class Sink:
                def __init__(self): self.directories=[]
                def keep(self,path,mode,oid,raw,sha): append(path,mode,oid,raw,sha)
            current(True)
            rebuild(Sink(),current,lambda n,t,size:phase(f"已按原内容编号写入独立暂存：{n}/{t} 个文件，{size/1048576:.1f} MiB"))
        outcome = publish(ctx,op,digest,manifest,feed,current,record,phase)
        complete = (outcome.get("status")=="completed" and outcome.get("verified") is True and outcome.get("delivered") is True
                    and outcome.get("path")==target and outcome.get("manifestDigest")==digest and _native_id(outcome.get("directoryIdentity")))
        status = "completed" if complete else "failed" if outcome.get("publicationAttempted") is False and outcome.get("status")=="failed" else "unknown"
        final = {**evidence,"status":status,"at":int(time.time()*1000)}
        if complete:
            final["root"] = outcome["directoryIdentity"]
            final["items"] = outcome["items"]
            if work_origin is not None: final["registeredRootOrigin"]=work_origin
        if status=="failed": final["cleanupConfirmed"] = outcome.get("cleanupPending") is False
        # Save only the factual result even if the progress window closed
        # after a physical publication. Never turn later cancellation into undo.
        _store(ctx,_phase(op,"result"),final,4096,ensure_state)
        return inspect(ctx,row)
