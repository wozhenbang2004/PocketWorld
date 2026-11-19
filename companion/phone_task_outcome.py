"""Project an authenticated Android goal result, not a generic task status summary."""
from __future__ import annotations

import device_protocol as wire


def is_phone_action_goal(row):
    scope = row.get("resourceBinding")
    return (row.get("kind") == "task.submitGoal" and isinstance(scope, dict) and
            (scope == {"screenMode":"capabilities"} and row.get("originalTextReturn") == wire.TEXT_RETURN or
             scope.get("screenMode") in ("main_screen", "background") and type(scope.get("androidUserId")) is int
             and isinstance(scope.get("packageNames"), list) and bool(scope["packageNames"])))


def original_phone_goal(row, task, caller):
    """Match native source dispatch, accepted receiver run, original audience and current
    phone snapshot. This is local correlation *after* the existing live history grant/mTLS read."""
    source, accepted = row.get("dispatchAudience"), row.get("acceptedAudience")
    if (not is_phone_action_goal(row) or row.get("accepted") is not True
            or not isinstance(source, dict) or not isinstance(accepted, dict) or not isinstance(task, dict)):
        raise PermissionError("Original accepted phone goal is not confirmed")
    operation = wire.identifier(row.get("operationId"), "operationId")
    target = wire.device_id(row.get("peerDeviceId"))
    instance = wire.identifier(row.get("instanceId"), "instanceId")
    task_id = wire.identifier(row.get("taskId"), "taskId")
    run = wire.identifier(row.get("runId"), "runId")
    generation = wire.integer(row.get("executorEpoch"), "executorEpoch", 1)
    grant = wire.identifier(row.get("grantId"), "grantId")
    epoch = wire.integer(source.get("authorizationEpoch"), "authorizationEpoch", 1)
    expected = dict(originDeviceId=caller, callerDeviceId=caller, targetDeviceId=target, instanceId=instance,
                    grantId=grant, authorizationEpoch=epoch, rootTaskId=operation, localTaskId=operation)
    if (any(source.get(k) != v or accepted.get(k) != v for k, v in expected.items())
            or not isinstance(row.get("resourceBinding"), dict)
            or not wire.resource_equal(row["resourceBinding"], source.get("resourceBinding"))
            or not wire.resource_equal(row["resourceBinding"], accepted.get("resourceBinding"))
            or task.get("messageType") != "TaskSnapshot" or task.get("executorDeviceId") != target or task.get("instanceId") != instance
            or task.get("taskId") != task_id or task.get("runId") != run or task.get("executorEpoch") != generation):
        raise PermissionError("Phone receipt does not match the original accepted work and run")
    details = task.get("result")
    origin = details.get("origin") if isinstance(details, dict) else None
    if (not isinstance(origin, dict) or details.get("executionKind") != "GOAL" or details.get("rootTaskId") != operation
            or details.get("remoteParentTaskId") != operation or origin.get("kind") != "paired_device"
            or origin.get("remoteOperationId") != operation or origin.get("remoteLocalTaskId") != operation
            or origin.get("rootTaskId") != operation or origin.get("parentTaskId") != operation or type(origin.get("authorizationEpoch")) is not int
            or any(origin.get(k) != expected[k] for k in ("originDeviceId", "callerDeviceId", "targetDeviceId", "grantId", "authorizationEpoch"))):
        raise PermissionError("Phone task is not the selected original dispatch")
    if row["resourceBinding"] == {"screenMode":"capabilities"} and origin.get("textReturnContract") != wire.TEXT_RETURN:
        raise PermissionError("The original phone did not accept the explicit text return contract")
    outcome = details.get("phoneGoalOutcome")
    review, summary, truncated = "unavailable", "", False
    if outcome is not None:
        if (not isinstance(outcome, dict) or outcome.get("review") not in ("confirmed", "none", "pending", "not_confirmed", "unverified", "unavailable")
                or not isinstance(outcome.get("summary"), str) or type(outcome.get("truncated")) is not bool):
            raise PermissionError("Original phone result could not be verified")
        review = outcome["review"]
        if review == "confirmed":
            summary, truncated = outcome["summary"], outcome["truncated"]
            if task.get("state") != "succeeded" or len(summary) > 2048:
                raise PermissionError("Phone completion and its bounded result disagree")
    return dict(operationId=operation, peerDeviceId=target, instanceId=instance, taskId=task_id, runId=run, executorEpoch=generation,
                review=review, summary=summary, truncated=truncated)


def original_dispatch_only(row, receipt, caller):
    """No task.get, receipt payload, cached result, question, control lease or runner text."""
    if not is_phone_action_goal(row) or not isinstance(receipt, dict):
        raise PermissionError("Only a locally recorded original phone goal has this receipt")
    op = wire.identifier(row.get("operationId"), "operationId")
    peer = wire.device_id(row.get("peerDeviceId"))
    instance = wire.identifier(row.get("instanceId"), "instanceId")
    source = row.get("dispatchAudience")
    if not isinstance(source, dict):
        raise PermissionError("Missing immutable original dispatch audience")
    expected = dict(originDeviceId=caller,callerDeviceId=caller,targetDeviceId=peer,instanceId=instance,rootTaskId=op,localTaskId=op,
                    grantId=wire.identifier(row.get("grantId"),"grantId"),authorizationEpoch=wire.integer(source.get("authorizationEpoch"),"authorizationEpoch",1),
                    resourceBinding=row["resourceBinding"])
    if (any(wire.canonical_bytes(source.get(k)) != wire.canonical_bytes(v) for k,v in expected.items())
            or receipt.get("messageType") != "OperationReceipt" or receipt.get("operationId") != op
            or receipt.get("executorDeviceId") != peer or receipt.get("instanceId") != instance or "cancellation" in receipt):
        raise PermissionError("The reply is not from the selected original dispatch")
    view = dict(operationId=op, peerDeviceId=peer, instanceId=instance, state=receipt["status"], accepted=receipt["accepted"])
    if receipt["accepted"] is False:
        if receipt.get("argumentsDigest") not in (None,row["argumentsDigest"]) or receipt["status"] not in ("unknown","not_found","rejected"):
            raise PermissionError("Unaccepted receipt does not match this original query")
        return view
    accepted = receipt["audience"]
    task = wire.receiver_task_id(dict(expected, messageType="GoalSubmission", operationId=op))
    if (receipt.get("accepted") is not True or receipt.get("argumentsDigest") != row["argumentsDigest"]
            or any(wire.canonical_bytes(accepted.get(k)) != wire.canonical_bytes(v) for k,v in expected.items())
            or accepted.get("kind") != "operation" or accepted.get("objectId") != op or accepted.get("taskId") != task
            or accepted.get("argumentsDigest") != row["argumentsDigest"] or receipt.get("localTaskId") != task
            or row.get("taskId",task) != task or accepted.get("acceptedAt") != receipt.get("acceptedAt")):
        raise PermissionError("Missing this phone's original accepted object")
    fixed={key:value for key,value in accepted.items() if key not in {"runId","executorEpoch","retainedUntil"}}
    if row.get("acceptedAudience") is not None and not wire.resource_equal(row["acceptedAudience"],fixed):
        raise PermissionError("The phone acceptance is not the committed source receipt")
    view.update(taskId=task,acceptedAt=wire.integer(receipt.get("acceptedAt"),"acceptedAt",1))
    if "runId" in receipt:
        run=wire.identifier(receipt["runId"],"runId"); epoch=wire.integer(receipt.get("executorEpoch"),"executorEpoch",1)
        if row.get("runId",run)!=run or row.get("executorEpoch",epoch)!=epoch or (accepted.get("runId"),accepted.get("executorEpoch"))!=(run,epoch):
            raise PermissionError("The original accepted phone run changed")
        view.update(runId=run,executorEpoch=epoch)
    elif row.get("runId") or accepted.get("runId") or "executorEpoch" in receipt:
        raise PermissionError("A prior confirmed run cannot become a never-started task")
    return view
