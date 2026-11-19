"""Project an authenticated Android goal result, not a generic task status summary."""
from __future__ import annotations

import device_protocol as wire


def is_phone_action_goal(row):
    scope = row.get("resourceBinding")
    return (row.get("kind") == "task.submitGoal" and isinstance(scope, dict) and scope.get("screenMode") in ("main_screen", "background")
            and type(scope.get("androidUserId")) is int and isinstance(scope.get("packageNames"), list) and bool(scope["packageNames"]))


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
