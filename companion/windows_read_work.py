"""Owner's local index of accepted reads from ONE source task, run, grant and Windows workspace."""
from dataclasses import dataclass
from windows_received_job import READS


@dataclass(frozen=True)
class ReadWork:
    caller: str
    target: str
    installation: str
    task: str
    local_task: str
    run: str
    executor_epoch: int
    grant: str
    authorization_epoch: int
    environment: str
    binding: str


def from_original(original):
    # admission() already verifies both records, exact direct audience, original Windows installation and workspace shape.
    source, accepted = original
    if source["toolName"] not in READS or "sourceShareId" in accepted:
        return None
    resource = accepted["resourceBinding"]
    return ReadWork(accepted["callerDeviceId"], accepted["targetDeviceId"], accepted["instanceId"],
                    accepted["rootTaskId"], accepted["localTaskId"], accepted["requestRunId"], accepted["requestExecutorEpoch"],
                    accepted["grantId"], accepted["authorizationEpoch"], resource["environmentId"], resource["binding"])


# Even an empty page advances by rowid; discover no unrelated job/body to populate a task group.
BATCH = """SELECT rid, operation_id, fingerprint, updated_at,
  CASE WHEN json_extract(doc,'$.protocol')='native-device-tool-v1'
    AND json_extract(doc,'$.audience.toolName') IN ('workspace.list','workspace.read')
    AND json_extract(doc,'$.audience.callerDeviceId')=? AND json_extract(doc,'$.audience.rootTaskId')=?
    AND json_extract(doc,'$.audience.requestRunId')=? AND json_extract(doc,'$.audience.resourceBinding.binding')=?
    THEN json_extract(doc,'$.audience.toolName') END FROM
  (SELECT rowid rid, operation_id, fingerprint, updated_at, CASE WHEN json_valid(details) THEN details END AS doc
   FROM operations WHERE rowid<? ORDER BY rowid DESC LIMIT 64) ORDER BY rid DESC"""


def row_label(receipt, item):
    from desktop_phone_receipts import _stamp
    phase = receipt["phase"]
    if phase in {"text_snapshot", "directory_snapshot"}:
        path = receipt["path"] or "工作区根目录"
        path = path if len(path) <= 92 else "…" + path[-91:]
        status = "版本 …" + receipt["version"][-12:] if phase == "text_snapshot" else f"当时返回 {receipt['returned']} 项"
    else:
        path, status = "原相对位置未核实", "只确认接单"
    verb = "读文本" if receipt["tool"] == "workspace.read" else "列目录"
    return f"{verb} · {path.replace('&', '&&')}\n{_stamp(receipt['updatedAt'])} · {status} · 操作 …{item[1][-8:]}"


def show(view, peer, anchor, work, anchor_current):
    import desktop_received_commands as received
    # We only read bounded native ledger pages. No other job file is opened for its label until the original matches.
    pages = [None]
    def current():
        return anchor_current() and received.local_record(view, peer, anchor, required_work=work)
    while current():
        rows, older = view._page(peer, pages[-1], incoming=True, read_work=work)
        choices, revisions = [], {}
        for item in rows:
            summary = received.local_record(view, peer, item, read=True, required_work=work)
            if not summary: continue
            choices.append((item, row_label(summary, item)))
            revisions[item] = summary.get("revision")
        if not current(): return
        def page_current():
            return current() and all(received.local_record(view, peer, item, required_work=work, revision=revision)
                                     for item, revision in revisions.items())
        if len(pages)>1: choices.append(("newer", "上一页"))
        if older is not None: choices.append(("older", "查看更早的原记录"))
        choices.append(("ids", "核对手机的原任务与运行编号"))
        body = (f"手机任务 …{work.task[-12:]} · 运行 …{work.run[-12:]}\n当时的本机工作区登记号：{work.environment}\n"
                + ("选择一条查看它当时的回执。" if revisions else "这一页没有可核对的读取。")
                + "只包括这一次运行在同一授权工作区实际接过的读取，不是完整的目录清单，也不证明目标已完成。")
        picked = view._dialog("这次手机任务在此工作区的读取", body, choices, page_current)
        if picked is None: return
        if picked == "older": pages.append(older); continue
        if picked == "newer": pages.pop(); continue
        if picked == "ids":
            view._dialog("和手机的原工作核对", f"手机任务：{work.task}\n原运行：{work.run}\n原执行代次：{work.executor_epoch}"
                         f"\n本机工作区登记号：{work.environment}\n原工作区绑定：{work.binding}", [], current)
        elif picked in revisions and page_current():
            received.show(view, peer, picked, required_work=work, owning_current=current)
