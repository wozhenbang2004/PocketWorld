"""Public direct-child observations of an already approved Codex app-server job.

Authored from the locally generated official 0.166.0-alpha.1 v2 schema. No thread
read/resume, child request approval, private reasoning, process start or new grant.
"""
import re

PROTOCOL = "codex_public_child_v1"
VERSION = "0.166.0-alpha.1"
PROOF = "thread_started_direct_subagent_v1"
MAX_CHILDREN = 16
TOOL_TYPES = frozenset({"commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "collabAgentToolCall", "webSearch", "imageView"})


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}", value) is not None


def public_text(value):
    if not isinstance(value, str) or "\0" in value:
        return False
    try:
        value.encode("utf-8")
        return True
    except UnicodeError:
        return False


class CodexChildSources:
    def __init__(self):
        self.children = {}
        self.conflicted = set()
        self.observation = 0

    def register(self, params, parent_thread, parent_turn, runtime_version):
        if runtime_version != VERSION or not identifier(parent_thread) or not identifier(parent_turn) or not isinstance(params, dict):
            return None
        thread = params.get("thread")
        if not isinstance(thread, dict) or not identifier(thread.get("id")) or thread["id"] == parent_thread:
            return None
        child = thread["id"]
        source = thread.get("source")
        sub = source.get("subAgent") if isinstance(source, dict) else None
        spawn = sub.get("thread_spawn") if isinstance(sub, dict) else None
        valid = (thread.get("cliVersion") == VERSION and thread.get("parentThreadId") == parent_thread
                 and isinstance(spawn, dict) and spawn.get("parent_thread_id") == parent_thread
                 and type(spawn.get("depth")) is int and spawn["depth"] == 1)
        identity = {"childSourceProtocol": PROTOCOL, "nativeLineageVersion": VERSION,
                    "nativeParentProof": PROOF, "nativeParentThreadId": parent_thread,
                    "nativeParentTurnId": parent_turn, "nativeChildThreadId": child}
        previous = self.children.get(child)
        if previous is not None and (not valid or previous != identity):
            self.children.pop(child, None)
            if len(self.conflicted) < MAX_CHILDREN:
                self.conflicted.add(child)
            return None
        if not valid or child in self.conflicted or previous is None and len(self.children) + len(self.conflicted) >= MAX_CHILDREN:
            return None
        self.children[child] = identity
        return self._row(identity, "child_source", "thread/started", child)

    def _row(self, identity, kind, method, item, **values):
        self.observation += 1
        return {"kind": kind, "nativeType": method, "itemId": item, **identity,
                "driverObservationIndex": self.observation, **values}

    def event(self, method, params, parent_thread, parent_turn, runtime_version):
        if runtime_version != VERSION or not isinstance(params, dict) or not isinstance(method, str) or not identifier(params.get("threadId")):
            return None
        identity = self.children.get(params.get("threadId"))
        if identity is None or (identity["nativeParentThreadId"], identity["nativeParentTurnId"]) != (parent_thread, parent_turn):
            return None
        if method == "turn/completed":
            turn = params.get("turn")
            if isinstance(turn, dict) and identifier(turn.get("id")) and isinstance(turn.get("status"), str) and turn["status"] in {"completed", "failed", "interrupted"}:
                return self._row(identity, "child_status", method, turn["id"], nativeChildTurnId=turn["id"], status=turn["status"], summary="Child turn status observed; not the parent final result or permission to resume")
            return None
        turn = params.get("turnId")
        if not identifier(turn):
            return None
        if method == "item/agentMessage/delta" and identifier(params.get("itemId")) and public_text(params.get("delta")):
            # No native per-delta event ID exists. Keep identical received chunks;
            # this local observation number is explicitly not a provider replay ID.
            return self._row(identity, "child_text", method, params["itemId"], nativeChildTurnId=turn, text=params["delta"], textForm="received_public_delta_not_complete_message")
        item = params.get("item")
        if method not in {"item/started", "item/completed"} or not isinstance(item, dict) or not identifier(item.get("id")):
            return None
        if method == "item/completed" and item.get("type") == "agentMessage" and public_text(item.get("text")):
            return self._row(identity, "child_text", method, item["id"], nativeChildTurnId=turn, text=item["text"], textForm="received_complete_public_message")
        if isinstance(item.get("type"), str) and item["type"] in TOOL_TYPES:
            return self._row(identity, "tool", method, item["id"], nativeChildTurnId=turn, tool=item["type"], status="started" if method == "item/started" else "completed", summary="Public child tool observation only; not approval, parent output, usage or a controllable child session")
        return None
