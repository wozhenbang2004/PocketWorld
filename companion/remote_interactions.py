"""Bounded protocol values on the ORIGINAL job; no server, transport or task store.

Normal questions never grant tool/file/command permissions. These objects are
snapshots, not an event stream or a claim of complete provider history.
"""
import copy
import hashlib
import json
import queue
import threading
import time
import uuid


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def _text(value, limit, name, empty=False):
    if not isinstance(value, str) or "\0" in value or len(value) > limit or not empty and not value.strip():
        raise ValueError("Unsupported or over-limit question " + name)
    return value


def question(protocol, context, values):
    maximum = 4 if protocol == "claude_stream_json" else 8
    if not isinstance(values, list) or not 1 <= len(values) <= maximum:
        raise ValueError("Question count must be between one and eight")
    normalized, ids, texts, secret, preview = [], set(), set(), False, False
    for index, value in enumerate(values):
        if not isinstance(value, dict):
            raise ValueError("Question must be an object")
        codex = protocol == "codex_app_server"
        required = {"id", "header", "question", "isOther", "isSecret", "options"} if codex else {"header", "question", "multiSelect", "options"}
        if not required.issubset(value):
            raise ValueError("Question capability fields are incomplete")
        identifier = _text(value.get("id") if codex else "q" + str(index), 200, "id")
        prompt = _text(value.get("question"), 4000, "text")
        if identifier in ids or prompt in texts:
            raise ValueError("Question identities/text must be unique in this request")
        ids.add(identifier); texts.add(prompt)
        header = _text(value.get("header", ""), 120 if codex else 12, "header", True)
        multiple = False if codex else value.get("multiSelect")
        if not isinstance(multiple, bool):
            raise ValueError("Question selection mode was not declared")
        private = value.get("isSecret", False)
        other = value.get("isOther", False) if codex else True
        if not isinstance(private, bool) or not isinstance(other, bool):
            raise ValueError("Question input capabilities are invalid")
        secret |= private
        options = value.get("options")
        if options is None and not codex:
            raise ValueError("Claude question options were not declared")
        if options is not None and (not isinstance(options, list) or not 1 <= len(options) <= 20):
            raise ValueError("Question option count is unsupported")
        if not codex and not 2 <= len(options) <= 4:
            raise ValueError("Claude question requires two to four original options")
        choices, labels = [], set()
        for option in options or []:
            if not isinstance(option, dict):
                raise ValueError("Question option must be an object")
            label = _text(option.get("label"), 300, "option label")
            if label in labels:
                raise ValueError("Question option labels must be unique")
            labels.add(label)
            choices.append({"label": label, "description": _text(option.get("description", ""), 1000, "option description", True)})
            preview |= "preview" in option
        normalized.append({"id": identifier, "header": header, "text": prompt,
                           "multiSelect": multiple, "allowOther": other or options is None,
                           "requiresPrivateInput": private, "options": choices})
    result = {"version": 1, "protocol": protocol, **context, "requestId": str(uuid.uuid4()),
              "questions": normalized, "canAnswer": not secret, "grantsPermissions": False,
              "previewOmitted": preview, "nativeQuestionsHash": fingerprint(values),
              "answerKind": "normal_user_input_not_tool_approval"}
    if secret:
        result["unsupportedReason"] = "Secret input is not collected or persisted here; stop or use the trusted host"
    if len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > 32768:
        raise ValueError("Question exceeds the bounded native review size")
    result["requestHash"] = fingerprint(result)
    return result


def answers_for(request, answers):
    if request.get("canAnswer") is not True or request.get("grantsPermissions") is not False:
        raise ValueError("This question cannot be answered through this scope")
    if not isinstance(answers, dict) or set(answers) != {q["id"] for q in request["questions"]}:
        raise ValueError("Answers must match every exact original question, and no other field")
    result = {}
    for q in request["questions"]:
        values = answers[q["id"]]
        if not isinstance(values, list) or not 1 <= len(values) <= (20 if q["multiSelect"] else 1):
            raise ValueError("Answer selection count does not match the original question")
        labels = {v["label"] for v in q["options"]}
        checked = [_text(value, 2000, "answer") for value in values]
        if len(set(checked)) != len(checked):
            raise ValueError("Duplicate answer selections are not accepted")
        if any(value not in labels for value in checked):
            if not q["allowOther"] or len(checked) != 1:
                raise ValueError("Custom text must be explicitly supported and not mixed with selected options")
        result[q["id"]] = checked
    if len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > 16384:
        raise ValueError("Answers exceed the bounded input size")
    return result


def wire_answers(request, answers):
    values = answers_for(request, answers)
    if request["protocol"] == "codex_app_server":
        return {"answers": {key: {"answers": value} for key, value in values.items()}}
    original, result = [], {}
    for q in request["questions"]:
        original.append({"question": q["text"], "header": q["header"],
                         "multiSelect": q["multiSelect"], "options": copy.deepcopy(q["options"])})
        result[q["text"]] = values[q["id"]] if q["multiSelect"] else values[q["id"]][0]
    return {"behavior": "allow", "updatedInput": {"questions": original, "answers": result}}


class QuestionMailbox:
    """No thread or persistence: the existing driver pumps this and saves via on_state."""
    def __init__(self):
        self.pending, self.receipts, self.originals = {}, [], {}
        self.queue = queue.Queue(maxsize=12)
        self.lock = threading.RLock()

    def register(self, request, wire_id, wire_questions=None):
        with self.lock:
            if len(self.pending) >= 12 or len(self.originals) >= 128:
                raise ValueError("Original interaction budget exceeded")
            self.pending[request["requestId"]] = {"view": copy.deepcopy(request), "wireId": wire_id, "queued": False,
                                                   "wireQuestions": copy.deepcopy(wire_questions)}
            self.originals[request["requestId"]] = {"view": copy.deepcopy(request), "wireId": wire_id}

    def snapshot(self):
        with self.lock:
            return ([copy.deepcopy(value["view"]) for value in self.pending.values()], copy.deepcopy(self.receipts))

    def answer(self, request_id, request_hash, answer_id, answers, live, save):
        if not isinstance(answer_id, str) or str(uuid.UUID(answer_id)) != answer_id:
            raise ValueError("Answer id must be the original native operation UUID")
        is_live = live()  # Never call host authority while holding the value mailbox lock.
        with self.lock:
            original = self.originals.get(request_id)
            if original is None or original["view"]["requestHash"] != request_hash:
                raise ValueError("Original question is not present or has changed")
            checked = answers_for(original["view"], answers)
            digest = fingerprint(checked)
            for old in self.receipts:
                if old["answerId"] == answer_id:
                    if (old["requestId"], old["requestHash"], old["answerHash"]) != (request_id, request_hash, digest):
                        raise ValueError("Answer id was already bound to another exact response")
                    return copy.deepcopy(old)
            entry = self.pending.get(request_id)
            if entry is None or entry["queued"] or not is_live:
                raise ValueError("Original question is no longer pending")
            receipt = {"answerId": answer_id, "requestId": request_id, "requestHash": request_hash,
                       "answerHash": digest, "status": "queued", "dispatched": False,
                       "answerConsumptionConfirmed": False, "businessCompleted": False}
            self.receipts.append(receipt); entry["queued"] = True
        save()  # Original job durable intent must precede queue/stdio.
        self.queue.put_nowait((request_id, answer_id, checked))
        return copy.deepcopy(receipt)

    def pump(self, live, send, save):
        while True:
            try:
                request_id, answer_id, answers = self.queue.get_nowait()
            except queue.Empty:
                return
            is_live = live()
            with self.lock:
                entry = self.pending.get(request_id)
                receipt = next(value for value in self.receipts if value["answerId"] == answer_id)
                if entry is None or not is_live:
                    receipt.update(status="rejected", dispatched=False, notDispatchedConfirmed=True)
                else:
                    receipt.update(status="sending", dispatched=None)
            save()
            if entry is None or not live():
                receipt.update(status="rejected", dispatched=False, notDispatchedConfirmed=True)
                save(); continue
            try:
                response = wire_answers(entry["view"], answers)
                if entry["wireQuestions"] is not None:
                    response["updatedInput"]["questions"] = copy.deepcopy(entry["wireQuestions"])
                send(entry["wireId"], response)
            except Exception:
                receipt.update(status="unknown", dispatched=None)
                save()
                raise
            with self.lock:
                receipt.update(status="sent", dispatched=True)
                self.pending.pop(request_id, None)
            save()  # Sent means pipe write, not provider acceptance or task completion.

    def resolve(self, wire_id, reason="resolved"):
        with self.lock:
            for request_id, original in self.originals.items():
                if original["wireId"] != wire_id:
                    continue
                self.pending.pop(request_id, None)
                for receipt in self.receipts:
                    if receipt["requestId"] == request_id:
                        sent = receipt.get("dispatched") is not False
                        receipt.update(status=("withdrawn" if reason == "withdrawn" else "resolved") if sent else "rejected",
                                       providerRequestResolved=True, resolution=reason)

    def finish(self):
        with self.lock:
            self.pending.clear()
            for receipt in self.receipts:
                if receipt["status"] in {"queued", "sending"}:
                    receipt.update(status="unknown", dispatched=None)


class Progress:
    def __init__(self, protocol, enabled=True):
        self.protocol, self.enabled = protocol, enabled
        self.generation, self.sequence, self.dropped = str(uuid.uuid4()), 0, 0
        self.events, self.last = [], {}
        self.lock = threading.RLock()

    def add(self, kind, native_type, item_id="", **values):
        with self.lock:
            self._add(kind, native_type, item_id, **values)

    def _add(self, kind, native_type, item_id="", **values):
        if not self.enabled:
            return
        row = {"kind": kind, "nativeType": str(native_type)[:200], "itemId": str(item_id)[:200]}
        for key, value in values.items():
            if isinstance(value, str):
                row[key] = value[:4000]
                if len(value) > 4000:
                    row[key + "Truncated"] = True
            elif value is None or isinstance(value, (bool, int)):
                row[key] = value
        key = kind + ":" + row["nativeType"] + ":" + row["itemId"]
        if row.get("childSourceProtocol") == "claude_complete_child_v1":
            # Same text/tool IDs in two original children are not the same observation.
            # This is a read-only deduplication key, never a child session or permission.
            key += ":" + fingerprint({k: row.get(k) for k in ("parentSessionId", "parentPromptRequestId", "parentToolUseId")})
        if row.get("childSourceProtocol") == "codex_public_child_v1":
            key += ":" + fingerprint({k: row.get(k) for k in ("nativeParentThreadId", "nativeParentTurnId", "nativeChildThreadId", "nativeChildTurnId")})
        digest = fingerprint(row)
        if self.last.get(key) == digest:
            return
        if len(self.last) >= 128:
            self.last.clear()
        self.last[key] = digest
        self.sequence += 1
        row.update(sequence=self.sequence, observedAt=int(time.time() * 1000))
        self.events.append(row)
        while len(self.events) > 64 or len(json.dumps(self.events, ensure_ascii=False).encode("utf-8")) > 24576:
            self.events.pop(0); self.dropped += 1

    def snapshot(self):
        with self.lock:
            return {"version": 1, "protocol": self.protocol, "generation": self.generation,
                    "available": self.enabled, "events": copy.deepcopy(self.events),
                    "lastSequence": self.sequence, "droppedCount": self.dropped,
                    "retainedFromSequence": self.events[0]["sequence"] if self.events else self.sequence,
                    "completeHistory": False, "liveStreaming": False,
                    "observation": "bounded_original_job_snapshot_not_live_event_stream"}
