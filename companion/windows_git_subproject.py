"""Local owner selection INSIDE an already registered Windows work root; no new device resource."""
from contextlib import ExitStack
import ntpath

from platform_windows import local_root,relative_parts
from windows_git_tree import _component
from windows_source_publisher import _origin

PROFILE="pocketworld.windows-owner-selected-under-root.v1"


def reject(reason="selected_project_changed"):
    from windows_git_source import Incomplete
    raise Incomplete(reason)


def parts_for(path):
    if not isinstance(path,str) or len(path)>1024: reject()
    parts=relative_parts(path); prefix=""
    if not 1<=len(parts)<=16 or "/".join(parts)!=path: reject()
    for piece in parts:
        name,_=_component(prefix,piece.encode("utf-8")); prefix=name+"/"
    return parts


def valid(value,install,environment):
    if not isinstance(value,dict) or set(value)!={"format","install","environmentId","path","parent","directories","git"}: return False
    if value["format"]!=PROFILE or value["install"]!=install or value["environmentId"]!=environment: return False
    try: parts=parts_for(value["path"])
    except Exception: return False
    def origin(v):
        return (isinstance(v,list) and len(v)==5 and all(type(n) is int and 0<=n<2**32 for n in v)
                and any(v[1:3]) and any(v[3:]))
    return (origin(value["parent"]) and origin(value["git"]) and isinstance(value["directories"],list)
            and len(value["directories"])==len(parts) and all(origin(v) for v in value["directories"]))


def parent(ctx,root,stack):
    # Both ancestry chains remain pinned. Root spelling or different drive letters alone
    # cannot prove the owner's installation/private state isn't inside, above, or the same object.
    lineage=[]; private=[]
    _,h,_=stack.enter_context(ctx.backend.directory(root,lineage=lineage))
    stack.enter_context(ctx.backend.directory(ctx.home,lineage=private))
    if not lineage or not private or lineage[-1] in private or private[-1] in lineage: reject("unsafe_layout")
    return _origin(ctx.backend.api,ctx.backend.api.info(h))


def within(ctx,environment,root,relative,stack):
    from windows_git_source import Pins
    origin=parent(ctx,root,stack); parts=parts_for(relative)
    pins=Pins(ctx.backend,root,stack); ids=[]
    for n in range(1,len(parts)+1):
        entry=pins.node("/".join(parts[:n]))
        if not entry or not entry[2]: reject()
        ids.append(_origin(pins.api,pins.api.info(entry[1])))
    dot=pins.node(relative+"/.git")  # No discovery walk or file-body read.
    if not dot: reject("not_repository")
    if not dot[2]: reject("external_layout")
    proof={"format":PROFILE,"install":ctx.install_state["installId"],"environmentId":environment,
           "path":relative,"parent":origin,"directories":ids,"git":_origin(pins.api,pins.api.info(dot[1]))}
    return proof,entry[0]


def pick(ctx,settings,environment,choose_folder,locked):
    from windows_git_source import registered
    root=dict(registered(ctx,settings)).get(environment)
    if not root or locked(): reject()
    with ExitStack() as stack:
        before=parent(ctx,root,stack)
        picked=choose_folder(root=root)
        if picked is None or locked(): return None
        actual=local_root(picked)
        try: relative=ntpath.relpath(actual,root).replace("\\","/")
        except ValueError: reject("outside_selected_root")
        if relative in ("",".","..") or relative.startswith("../") or ntpath.isabs(relative): reject("outside_selected_root")
        evidence,_=within(ctx,environment,root,relative,stack)
        if locked() or evidence["parent"]!=before or dict(registered(ctx,settings)).get(environment)!=root: reject()
        return evidence


def source_pins(ctx,root,environment,selection,stack):
    from windows_git_source import Pins
    if selection is None: return Pins(ctx.backend,root,stack)  # Original, directly registered-root flow.
    if not valid(selection,ctx.install_state["installId"],environment): reject()
    current,physical=within(ctx,environment,root,selection["path"],stack)
    if current!=selection: reject()
    # Keep the original parent, all components and .git open while the source reader
    # consults this same physical project. Never add the child to the device's environments.
    pins=Pins(ctx.backend,physical,stack); dot=pins.node(".git")
    if (not dot or not dot[2] or current["directories"][-1]!=_origin(pins.api,pins.api.info(pins.nodes[()][1]))
            or current["git"]!=_origin(pins.api,pins.api.info(dot[1]))): reject()
    return pins
