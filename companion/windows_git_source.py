"""Read-only native Git source preflight for one owner-registered Windows workspace.

No Git or shell process, discovery in parents/children, filters/hooks, writes,
checkout, clean-worktree assertion or projectProduction admission.
"""
from contextlib import ExitStack
import hashlib
import json
import ntpath
import re
import time
import zlib

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, local_root, relative_parts
from windows_git_pack import PackUnavailable, PinnedPacks
from windows_git_contents import ContentProblem, measure
from windows_git_tree import TreeIssue, entries, review
from windows_installation import _object, same_path

PROFILE = "pocketworld.windows-registered-git-source.v1"


class Incomplete(Exception):
    def __init__(self, reason): self.reason = reason


def _fail(reason):
    raise Incomplete(reason)


def registered(context, settings_digest):
    context.check_current_pointer()
    if (context._read(context.home,"install-state.json",32768) != context.install_state
            or context.backend.private_leaf_present(context.home,"installation-transaction.json",context.sid,maximum=262144)):
        _fail("configuration_changed")
    raw = context._raw(context.state, "companion.json", 262144)
    if hashlib.sha256(raw).hexdigest() != settings_digest: _fail("configuration_changed")
    config = _object(raw)
    if (not same_path(config.get("stateDir"),context.jobs) or not same_path(config.get("tokenFile"),ntpath.join(context.state,"companion-token.txt"))
            or not isinstance(config.get("devices"),dict) or not same_path(config["devices"].get("identityDir"),context.identity_dir)):
        _fail("configuration_changed")
    environments = config.get("environments")
    if not isinstance(environments, list) or not 1 <= len(environments) <= 128: _fail("configuration_changed")
    selected, seen = [], set()
    for item in environments:
        if not isinstance(item, dict): _fail("configuration_changed")
        key = item.get("id")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", key) or key in seen: _fail("configuration_changed")
        seen.add(key)
        if item.get("kind", "local") != "local": continue
        try: root = local_root(item.get("root"))
        except (TypeError, PlatformError): continue
        try: overlap = ntpath.commonpath([root.casefold(), context.home.casefold()])
        except ValueError: overlap = None  # different local drive
        if overlap not in (root.casefold(), context.home.casefold()): selected.append((key, root))
    return selected


class Pins:
    """Hold original directory/file handles throughout observation. Never lock a service or write a file."""
    def __init__(self, backend, root, stack):
        self.api, self.stack = backend.api, stack
        path, handle, identity = stack.enter_context(backend.directory(root))
        self.nodes, self.missing = {(): (path, handle, True, identity)}, set()

    def _child(self, parent_entry, name, stack):
        parent, _, directory, _ = parent_entry
        if not directory: _fail("unsafe_layout")
        target = ntpath.join(parent, name)
        try: handle = self.api.open(target, directory=True)  # open reparse point itself, share READ only
        except OSError as error:
            if getattr(error, "winerror", None) != 2: raise
            return None
        stack.callback(self.api.k.CloseHandle, handle)
        info, actual = self.api.info(handle), self.api.final(handle)
        if (actual.casefold() != ("\\\\?\\"+target).casefold() or ntpath.basename(actual) != name
                or info.attributes & 0x400 or not info.attributes & 0x10 and info.links != 1):
            _fail("unsafe_layout")  # No junction, symlink, hardlink, 8.3 alias or case-changed Git ref.
        return target, handle, bool(info.attributes & 0x10), self.api.identity(info)

    def node(self, path):
        parts = tuple(relative_parts(path))
        for n in range(1,len(parts)+1):
            prefix = parts[:n]
            if prefix in self.nodes: continue
            if prefix in self.missing: return None
            item = self._child(self.nodes[prefix[:-1]],prefix[-1],self.stack)
            if item is None: self.missing.add(prefix); return None
            self.nodes[prefix] = item
        return self.nodes[parts]

    def reader(self, path, limit):
        return self._reader(self.node(path),limit)

    def _reader(self, entry, limit):
        if entry is None: return None
        if entry[2]: _fail("unsafe_layout")
        info = self.api.info(entry[1])
        if (info.sizeHigh << 32) + info.sizeLow > limit: _fail("limit")
        reader = WindowsHandleReader(self.api,entry[1],limit)
        if reader.identity != entry[3]: _fail("changed")
        return reader

    @staticmethod
    def read_all(reader, charge):
        if reader is None: return None
        if charge is not None: charge(reader.size)
        # A cached handle may have been used before. Read its original version
        # from the beginning, not the previous reader's shared file cursor.
        reader.seek(0)
        chunks = []
        while reader.position < reader.size:
            chunks.append(reader.read(min(262144,reader.size-reader.position)))
        reader.verify_unchanged()
        return b"".join(chunks)

    def file(self, path, limit, charge=None):
        return self.read_all(self.reader(path,limit),charge)

    def blob_file(self, path, limit, charge):
        # The source/ref and object directories stay pinned. Release each loose
        # blob after reading its exact original; retain only a digest, not
        # thousands of deny-write handles. A later copy must reopen and verify.
        parent,_,name = path.rpartition("/")
        directory = self.node(parent)
        if directory is None: return None
        with ExitStack() as leaf:
            return self.read_all(self._reader(self._child(directory,name,leaf),limit),charge)

    def recheck_absence(self, checkpoint):
        # In particular: a new loose ref shadows packed-refs. Validate absent
        # paths again, while their original parents and selected objects are still pinned.
        for i,components in enumerate(self.missing):
            if not i%16: checkpoint()
            target = ntpath.join(self.nodes[components[:-1]][0], components[-1])
            try: handle = self.api.open(target, directory=True)
            except OSError as error:
                if getattr(error, "winerror", None) == 2: continue
                raise
            self.api.k.CloseHandle(handle)
            _fail("changed")


def _format(raw):
    # Restricted physical repository format, not a Git command's environment.
    # Never expand includes, look up credentials or apply Git configuration.
    if raw is None: _fail("config_format")
    section, seen = None, {}
    for line in raw.removeprefix(b"\xef\xbb\xbf").splitlines():
        line = line.strip()
        if not line or line.startswith((b"#", b";")): continue
        if line.endswith(b"\\"): _fail("config_format")
        if line.startswith(b"["):
            match = re.fullmatch(rb'\[([a-zA-Z0-9.-]+)(?:[ \t]+"(?:[^"\\]|\\.)*")?\][ \t]*(?:[#;].*)?', line)
            if not match: _fail("config_format")
            section = match[1].lower()
            if section.split(b".", 1)[0] in {b"include", b"includeif"}: _fail("external_layout")
            if (section.startswith((b"core.", b"extensions.")) or section in {b"core", b"extensions"} and b'"' in line): _fail("config_format")
            continue
        if section not in {b"core", b"extensions"}: continue
        match = re.fullmatch(rb"([a-zA-Z][a-zA-Z0-9-]*)(?:[ \t]*=[ \t]*(.*))?", line)
        if not match: _fail("config_format")
        key = (section, match[1].lower())
        if key == (b"core", b"worktree"): _fail("external_layout")
        if key[0] == b"extensions" and key[1] not in {b"objectformat", b"refstorage"}: _fail("config_format")
        if key[0] == b"extensions" or key[1] in {b"repositoryformatversion", b"bare"}:
            if key in seen: _fail("config_format")
            seen[key] = (match[2] or b"").split(b"#",1)[0].split(b";",1)[0].strip().lower()
    version = seen.get((b"core",b"repositoryformatversion"))
    if (version not in {b"0",b"1"} or seen.get((b"core",b"bare")) != b"false"
            or version == b"0" and any(section == b"extensions" for section,_key in seen)
            or seen.get((b"extensions",b"refstorage"),b"files") != b"files"): _fail("config_format")
    algo = seen.get((b"extensions",b"objectformat"),b"sha1")
    if algo not in {b"sha1",b"sha256"}: _fail("config_format")
    return algo.decode("ascii")


def _line(raw):
    if raw is None: _fail("head_unresolved")
    line = raw.removesuffix(b"\n").removesuffix(b"\r")
    if not line or any(c < 32 or c == 127 for c in line): _fail("head_unresolved")
    try: return line.decode("utf-8")
    except UnicodeError: _fail("head_unresolved")


def _branch(value):
    if (not value.startswith("refs/heads/") or len(value) > 240 or value.endswith(("/","."))
            or ".." in value or "@{" in value or any(c in value for c in " ~^:?*[\\")
            or any(p.startswith(".") or p.endswith(".lock") for p in value.split("/"))): _fail("ref_format")
    try: relative_parts(value)
    except PlatformError: _fail("ref_format")
    return value


def _head(pins, algo):
    head = _line(pins.file(".git/HEAD", 1024))
    pattern = r"[a-f0-9]{"+str(hashlib.new(algo).digest_size*2)+"}"
    if re.fullmatch(pattern,head): return head, None
    if not head.startswith("ref: "): _fail("head_unresolved")
    ref = _branch(head[5:])
    raw = pins.file(".git/"+ref, 1024)
    if raw is not None:
        value = _line(raw)
        if not re.fullmatch(pattern, value): _fail("ref_format")
        return value, ref
    packed = pins.file(".git/packed-refs", 1024*1024)
    if packed is None: _fail("head_unresolved")
    found = None
    for line in packed.splitlines():
        if line.startswith((b"#",b"^")): continue
        split = line.split(b" ",1)
        if len(split) != 2 or split[1] != ref.encode(): continue
        value = split[0].decode("ascii")
        if found is not None or not re.fullmatch(pattern,value): _fail("ref_format")
        found = value
    if found is None: _fail("head_unresolved")
    return found, ref


def _object_body(pins, packs, oid, kind, limit):
    read = pins.blob_file if kind==b"blob" else pins.file
    raw = read(".git/objects/"+oid[:2]+"/"+oid[2:],limit+65536,packs.budget.charge)
    if raw is None: return packs.get(oid,kind,limit), "pack"
    try:
        decoder = zlib.decompressobj(); decoded = decoder.decompress(raw,limit+64)
    except zlib.error: _fail("invalid_object")
    if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail: _fail("invalid_object")
    header, nul, body = decoded.partition(b"\0")
    if (not nul or len(body)>limit or header != kind+b" "+str(len(body)).encode()
            or hashlib.new(packs.algo,decoded).hexdigest()!=oid): _fail("invalid_object")
    packs.budget.work(len(body))
    return body, "loose"


def _tree_id(body, algo):
    headers, separator, _ = body.partition(b"\n\n")
    lines = headers.split(b"\n")
    if not separator or b"\0" in headers or not lines or not lines[0].startswith(b"tree "): _fail("invalid_object")
    oid = lines[0][5:]
    pattern = rb"[0-9a-f]{"+str(hashlib.new(algo).digest_size*2).encode()+rb"}"
    if (not re.fullmatch(pattern,oid) or sum(x.startswith(b"tree ") for x in lines)!=1
            or sum(x.startswith(b"author ") for x in lines)!=1 or sum(x.startswith(b"committer ") for x in lines)!=1
            or any(not re.fullmatch(pattern,x[7:]) for x in lines if x.startswith(b"parent "))): _fail("invalid_object")
    return oid.decode("ascii")


def repository_anchor(context,environment_id,pins,dot):
    parts = [PROFILE,context.install_state["installId"],environment_id,pins.nodes[()][3],dot[3]]
    return hashlib.sha256(json.dumps(parts,separators=(",",":")).encode()).hexdigest()


def capture(context, settings_digest, environment_id, *, selected_project=None, layout_for=None, content=False, checkpoint=lambda: None, on_count=lambda *_: None, material=None):
    facts = {"format": PROFILE, "environmentId": environment_id, "sourceVerified": False, "projectProduction": False,
             "scope": "committed_head_and_root_tree_only", "treeRecursivelyVerified": False,
             "workingTreeChecked": False, "projectCopied": False, "capturedAt": int(time.time()*1000)}
    try:
        checkpoint()
        if material is not None and not content: _fail("original_mapping_changed")
        if content and (not isinstance(layout_for,dict) or layout_for.get("committedNameReview",{}).get("complete") is not True):
            _fail("original_mapping_changed")
        root = dict(registered(context, settings_digest)).get(environment_id)
        if not root: _fail("configuration_changed")
        if layout_for is not None:
            if selected_project is not None: _fail("selected_project_changed")
            if "selectedProject" in layout_for:
                selected_project=layout_for["selectedProject"]
                if selected_project is None: _fail("selected_project_changed")
        from windows_git_subproject import source_pins
        with ExitStack() as stack:
            pins=source_pins(context,root,environment_id,selected_project,stack)
            dot = pins.node(".git")
            if dot is None: _fail("not_repository")
            if not dot[2]: _fail("external_layout")
            # Recheck this installed workspace AND its .git before opening a
            # replacement's refs. Pin the original head/branch before expanding
            # data; a new tip isn't consent to review that other version.
            anchor = repository_anchor(context,environment_id,pins,dot)
            if layout_for is not None:
                expected = {"format":PROFILE,"sourceVerified":True,"environmentId":environment_id,"repositoryAnchor":anchor}
                if any(layout_for.get(k)!=v for k,v in expected.items()): _fail("original_source_changed")
            if pins.node(".git/commondir") is not None or pins.node(".git/objects/info/alternates") is not None: _fail("external_layout")
            algo = _format(pins.file(".git/config",128*1024))
            if layout_for is not None and layout_for.get("objectFormat")!=algo: _fail("original_source_changed")
            head, branch = _head(pins,algo)
            if layout_for is not None and (layout_for.get("commit"),layout_for.get("branch"))!=(head,branch):
                _fail("original_source_changed")
            packs = PinnedPacks(pins,algo,tree_walk=layout_for is not None,content=content,checkpoint=checkpoint)
            commit_body, commit_storage = _object_body(pins,packs,head,b"commit",256*1024)
            tree = _tree_id(commit_body,algo)
            if layout_for is not None and layout_for.get("tree")!=tree: _fail("original_source_changed")
            tree_body, tree_storage = _object_body(pins,packs,tree,b"tree",1024*1024)
            count = sum(1 for _ in entries(tree_body,packs.width))
            path_review, content_review = None, None
            selected = [] if content else None
            if layout_for is not None:
                try:
                    path_review = review(tree,tree_body,packs.width,
                        lambda oid: _object_body(pins,packs,oid,b"tree",1024*1024)[0],pins.api,checkpoint,collect=selected,
                        directories=None if material is None else material.directories)
                except (Incomplete,TreeIssue,PackUnavailable) as error:
                    path_review = {"complete":False,"reason":error.reason,"example":getattr(error,"example",None)}
                except (PlatformError,OSError,ValueError,TypeError):
                    path_review = {"complete":False,"reason":"native_read_unavailable"}
            if content and path_review["complete"]:
                original = layout_for["committedNameReview"]
                if any(original.get(k)!=path_review[k] for k in ("profile","mappingDigest","files","directories")):
                    _fail("original_mapping_changed")
                # Only now are regular blobs in the exact reviewed tree read.
                try:
                    content_review = measure(selected,path_review["mappingDigest"],packs.width,
                        lambda oid,limit: _object_body(pins,packs,oid,b"blob",limit)[0],checkpoint,on_count,material)
                except (ContentProblem,Incomplete,PackUnavailable) as error:
                    content_review = {"complete":False,"reason":error.reason}
                except (PlatformError,OSError,ValueError,TypeError):
                    content_review = {"complete":False,"reason":"native_read_unavailable"}
            checkpoint()
            if dict(registered(context,settings_digest)).get(environment_id)!=root: _fail("configuration_changed")
            pins.recheck_absence(checkpoint)
            if path_review is not None:
                facts["committedNameReview"] = path_review
                if path_review["complete"]: facts["scope"] = "committed_directory_name_records"
            if content_review is not None:
                facts["committedContentReview"] = content_review
                if content_review["complete"]:
                    facts.update(scope="raw_committed_tree_and_blob_content",treeRecursivelyVerified=True,
                                 workingTreeChecked=False,atomicWorkingTreeSnapshot=False)
            if selected_project is not None: facts["selectedProject"]=selected_project
            facts.update(sourceVerified=True, repositoryAnchor=anchor, objectFormat=algo, commit=head, tree=tree,
                         branch=branch, rootEntries=count, objectStorage={"commit":commit_storage,"rootTree":tree_storage},
                         capturedAt=int(time.time()*1000))
    except (Incomplete,TreeIssue,PackUnavailable) as error: facts["reason"] = error.reason
    except (PlatformError,OSError,ValueError,TypeError): facts["reason"] = "native_read_unavailable"
    return facts
