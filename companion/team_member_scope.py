"""A narrowing of the existing Claude driver's tool scope, never an OS sandbox."""


def environment(request, env, error):
    if "teamReadOnlyTools" not in request:
        return env
    if request.get("teamReadOnlyTools") is not True or request.get("worker") != "claude":
        raise error("Team read-only tools require the native Claude protocol", 409)
    # A paired caller may narrow the environment, never expand it. The same
    # narrowed snapshot feeds argv construction, the driver and resume checks.
    return {**env, "writeAllowed": False}


def receipt(request):
    if request.get("teamReadOnlyTools") is True:
        return {"teamReadOnlyTools": True, "osReadIsolationVerified": False}
    return {}
