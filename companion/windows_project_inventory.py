"""Browse only the originally verified project's included inventory on its receiving Windows desktop."""
import time
import unicodedata

import device_protocol as protocol
from assignment_projects import manifest_identity
from device_project_packages import MAX_FILE, MAX_FILES, MAX_TOTAL, path as package_path
from platform_backend import PlatformError
from windows_received_job import PROJECT_RECEIVE
from windows_received_project import READ_ERRORS, SHA, project


def _tree(manifest, hashes):
    """No omitted entries, private names, ZIP data, source files, or current directory listings."""
    declared, directories = manifest["files"], manifest["directories"]
    if not isinstance(declared, list) or not 1 <= len(declared) <= MAX_FILES or not isinstance(directories, list) or len(directories) > MAX_FILES:
        raise PlatformError("Unknown original included file count")
    generated = manifest["policy"]["generatedRootsIncluded"]
    if type(generated) is not bool or manifest["policy"]["privateFilesExcluded"] is not True: raise PlatformError("Unknown project policy")
    tree, total = {(): {}}, 0

    def parts(name):
        checked = package_path(name, generated)
        if any(unicodedata.category(c).startswith("C") for c in checked): raise PlatformError("Cannot faithfully display the original path")
        return tuple(checked.split("/"))

    def folders(pieces):
        for i in range(len(pieces)):
            parent, name = pieces[:i], pieces[i]
            if tree[parent].get(name) is not None: raise PlatformError("File is a directory parent")
            tree[parent][name] = None
            tree.setdefault(pieces[:i + 1], {})
            if len(tree) > MAX_FILES + 1: raise PlatformError("Too many original directories")

    for directory in directories: folders(parts(directory))
    for item in declared:
        full = parts(item["path"]); folder, name = full[:-1], full[-1]
        folders(folder)
        size, digest = item["bytes"], item["sha256"]
        if (name in tree[folder] or type(size) is not int or not 0 <= size <= MAX_FILE
                or not isinstance(digest, str) or not SHA.fullmatch(digest)):
            raise PlatformError("Included original file identity unavailable")
        tree[folder][name] = (size, digest if hashes else None)
        total += size
    if len(tree) > MAX_FILES + 1 or type(manifest["bytes"]) is not int or total != manifest["bytes"] or total > MAX_TOTAL:
        raise PlatformError("Incomplete original inventory")
    return tree, len(declared), total


def access(manager, peer, operation, row, original, grant, now, revision, mode, ticket):
    # The enclosing original operation.get/peer/retention checks are necessary, not sufficient to reveal filenames.
    source, audience = original
    try:
        if (source["toolName"] != PROJECT_RECEIVE or "sourceShareId" in audience or type(revision) is not int or revision <= 0
                or grant["expiresAt"] <= now or "tool.invoke" not in grant["capabilities"]
                or not {PROJECT_RECEIVE, "workspace.list"}.issubset(grant["toolNames"])): return None
        binding = audience["resourceBinding"]
        job = manager.jobs[row[1]]
        result = job.get("nativeToolResult") or {}
        digest = result.get("manifestDigest")
        if (job["nativeDeviceRevision"] != revision or job["status"] != "completed" or result.get("projectImported") is not True
                or result.get("verified") is not True or not isinstance(digest, str) or not SHA.fullmatch(digest)): return None
        # Explicit original package export plus live artifact visibility is required for per-file digest disclosure.
        hashes = ("workspace.project.export" in grant["toolNames"] and {"artifact.describe", "artifact.readChunk"}.issubset(grant["capabilities"]))
        key = (operation, row[0], row[1], revision, digest, binding["binding"], grant["grantId"], peer, hashes)
        if mode == "offer": return key  # Never touch the workspace to show this optional button.
        if mode not in ("current", "open") or key != ticket: return None
        from device_companion import workspace_binding
        eid = binding["environmentId"]
        if eid not in manager.environments or not protocol.resource_equal(workspace_binding(manager, eid), binding): return None
        if mode == "current": return key
        # Re-read the actual private job and verify its final original readback; never search for a project by name.
        proof = project(manager, peer, operation, row, original)
        if proof.get("phase") != "published" or proof.get("revision") != revision or proof.get("manifest") != digest: return None
        manifest = job.get("_nativeProjectManifest")
        if not isinstance(manifest, dict) or manifest_identity(manifest) != digest: return None
        return _tree(manifest, hashes)
    except READ_ERRORS:
        return None


def browse(view, peer, item, receipt, ticket, owner_current):
    from desktop_received_commands import local_record
    if not isinstance(ticket, tuple) or len(ticket) != 9: return
    until = time.monotonic() + 60
    def read(mode):
        return local_record(view, peer, item, revision=receipt["revision"], project_inventory=mode, ticket=ticket)
    def current():
        return time.monotonic() < until and owner_current() and read("current") == ticket
    catalog = read("open") if current() else None
    if not catalog or not current():
        if owner_current(): view._dialog("没有展开这份原清单", "没有同时核实发布回执、原清单、同一工作区和本机仍有效的查看范围。没有枚举现在的目录或读取正文。", [], owner_current)
        return
    tree, count, size = catalog
    folder, page = (), 0
    while current():
        rows = sorted(tree[folder].items(), key=lambda pair: (pair[1] is not None, pair[0].casefold(), pair[0]))
        options = [(("entry", name), ("文件夹：" if file is None else "文件：") + name
                    + ("\n只看原清单中的下一级" if file is None else f"\n原包内 {file[0]} 字节")) for name, file in rows[page*6:page*6+6]]
        if page: options.append(("prev", "上一页"))
        if (page+1)*6 < len(rows): options.append(("next", "下一页"))
        if folder: options.append(("up", "返回上一级目录"))
        body = (f"当时实际交付的项目：{count} 个文件 · {size} 字节。当前层：{'/'.join(folder) or '项目根目录'}。"
                "\n这是原包清单，不是今天的磁盘。未列排除项，不读取内容或打开文件。")
        if not rows: body += "\n此目录在原清单中没有子条目。"
        action = view._dialog("原项目包含哪些文件？", body, options, current)
        if action == "prev": page -= 1
        elif action == "next": page += 1
        elif action == "up": folder, page = folder[:-1], 0
        elif isinstance(action, tuple) and action[0] == "entry" and action[1] in tree[folder] and current():
            name = action[1]; record = tree[folder][name]; full = folder + (name,)
            if record is None: folder, page = full, 0; continue
            length, digest = record
            message = f"原包内路径：{'/'.join(full)}\n原长度：{length} 字节。"
            message += "\n原文件 SHA-256：" + digest if digest else "\n当前范围只展示清单，不显示逐文件摘要。"
            if view._dialog("原交付清单中的文件", message+"\n未检查今天磁盘上的文件或其内容。", [("back", "返回这一层")], current) != "back": return
        else: return
