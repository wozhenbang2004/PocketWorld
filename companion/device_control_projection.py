"""Bounded projections of external tool metadata, NOT a second wire protocol.

The shared DeviceProtocol remains the final frame validator. Keep ample room
for its envelope, original job/audience, artifact refs and action receipt.
"""
import json
import math


def project(value, *, maximum=24576, nodes=1400, depth=8):
    used, size, omitted = 0, 0, False
    def visit(item, level):
        nonlocal used, size, omitted
        used += 1
        if level > depth or used > nodes - 24 or size > maximum - 512:
            omitted = True
            return None
        if isinstance(item, float):
            item = str(item) if math.isfinite(item) else None
        elif type(item) is int and abs(item) > 9007199254740991:
            item = str(item)
        if item is None or type(item) in (str, bool, int):
            try:
                encoded = json.dumps(item, ensure_ascii=False, allow_nan=False).encode()
            except (ValueError, UnicodeError):
                omitted = True
                return None
            if len(encoded) > maximum - size - 512:
                omitted = True
                return None  # Never silently make a clipped UIA value match.
            size += len(encoded) + 1
            return item
        if isinstance(item, (list, dict)):
            result = [] if isinstance(item, list) else {}
            size += 2
            source = enumerate(item) if isinstance(item, list) else item.items()
            for key, child in source:
                if used >= nodes - 24 or size > maximum - 512:
                    omitted = True
                    break
                if isinstance(item, dict):
                    if not isinstance(key, str) or visit(key, level + 1) != key:
                        omitted = True
                        continue
                    result[key] = visit(child, level + 1)
                else:
                    result.append(visit(child, level + 1))
            return result
        omitted = True
        return None
    result = visit(value, 0)
    if omitted:
        if not isinstance(result, dict):
            result = {"items": result}
        result.update(truncated=True, adapter_truncated=True, hasMore=True)
        if "elements_complete" in result or "elements" in result:
            result["elements_complete"] = False
    return result


def validate_tool_result(envelope, result):
    """result must be the shared typed receipt, including its original audience."""
    from device_protocol import VERSION, PROFILE, validate_message
    return validate_message({"protocolVersion": VERSION, "protocolProfile": PROFILE, "messageType": "DeviceResponse", "requestId": envelope.get("requestId"),
                             "method": "tool.invoke", "executorDeviceId": envelope.get("targetDeviceId"),
                             "instanceId": envelope.get("instanceId"), "result": result})
