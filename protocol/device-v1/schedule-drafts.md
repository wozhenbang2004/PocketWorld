# Paired-device schedule drafts

The Android receiver supports two tools in the existing `ToolInvocation` extension point. They store or query an inert schedule proposal in the existing Automation book. They do not create a receiving task, call a model, acquire a phone resource, or schedule an alarm. The owner reviews the retained proposal in the local Plans surface before it becomes a rule. A phone goal still needs the existing owner start action when due.

The protocol version, profile, schema and signing format are unchanged. Clients must first use the existing authenticated pairing and discovery flow. An advertised capability is metadata; the exact receiving-device grant remains required.

## Explicit grant

The native grant picker exposes the `schedule_drafts` option only when the Automation module is installed. It is a separate, initially ungranted scope:

| Field | Required value |
| --- | --- |
| `capabilities` | `device.describe`, `tool.invoke` |
| `toolNames` | `automation_propose_schedule`, `automation_get_schedule_draft` |
| `resources` | Exactly `[{"screenMode":"capabilities"}]` |
| `allowedOrigins` | Exactly the directly paired caller device |
| `toolBudget`, `modelBudget` | Both `0` |
| `allowDelegation`, `maxDelegationDepth` | `false`, `0` |
| `forwardingScope`, `historyRead` | Absent |

The receiver, original TLS peer, certificate fingerprint, grant, authorization epoch, expiry and exact grant body are checked again at the original SQLite admission boundary. An old request cannot use a replacement grant. The receiver must be enabled for a new proposal to enter; a read already admitted on the original connection does not enter the new-write gate.

## Submit one proposal

Use `method: "tool.invoke"`, `messageType: "ToolInvocation"`, `capabilityVersion: "1.0"`, the exact resource above, and the existing complete envelope. The envelope's `budget.toolCalls` and `budget.modelCalls` must both be zero. The usual root/local/run/attempt correlation fields remain required by the protocol; they are not a receiving task or execution authority.

The `arguments` member is:

```json
{
  "toolName": "automation_propose_schedule",
  "effect": "write",
  "arguments": {
    "title": "Review the weekly statement",
    "kind": "reminder",
    "schedule": {
      "type": "weekly",
      "time": "18:00",
      "daysOfWeek": [5],
      "zone": "Asia/Shanghai"
    }
  }
}
```

`title` must be a nonblank string of at most 200 UTF-16 code units. `kind` is `reminder` or `phone_goal`; only `phone_goal` carries the required nonblank `goal` of at most 4,000 code units. Calendar definitions use the existing Automation normalizer and version rules (`once`, `interval`, `daily`, `weekly`, `monthly`, or the supported `cron` subset). No approval, policy, source, observation, dependency or semantic-condition fields are accepted here.

Persist a UUID `operationId` and the existing protocol `argumentsDigest` before sending the first write. The digest covers the whole semantic envelope under `DeviceProtocol.argumentsDigest`, including its original deadline and correlation fields. Reusing that original operation with changed content or authority is rejected. A received duplicate with the exact original identity returns the same proposal without another write or rule.

The original Book limits one caller/target pair to one new proposal per 30 seconds and eight per hour. Replacing a grant does not reset that history. Queries and exact duplicates do not consume a new slot. The Book's existing 100-proposal capacity also applies.

## Original result and uncertain delivery

The normal result is an existing `OperationReceipt`, with `status: "succeeded"`, `accepted: true` and `effectState: "reported"`. Here success means the inert draft was retained. Its audience comes from the stored original proposal and contains `requestRunId` / `requestExecutorEpoch` as correlation, without inventing a receiving `runId` / `executorEpoch`.

The body-free payload includes the original `proposalId`, `proposalDigest`, current draft `status`, `sourceCheckPending`, and:

```json
{
  "draftFound": true,
  "reviewRequired": true,
  "schedulingEnteredByIngress": false,
  "executionAuthorized": false,
  "modelCalled": false,
  "receiverTaskCreated": false,
  "replayAllowed": false,
  "bodyIncluded": false
}
```

`reviewRequired` becomes false once that proposal has already been approved locally. This does not report task completion or grant execution to the client. `schedulingEnteredByIngress` describes this ingress action; the proposal's later owner-approved state is reported separately.

After a timeout, lost acknowledgement, or `unknown` receipt, do not submit a new operation or alter/replay the old write. Query its original identity with a new read envelope:

For `unknown`, the existing protocol requires `accepted: false` and `effectState: "unknown"`: no committed acceptance time or audience has been confirmed. This does **not** prove the draft was not saved. Interpret status and effect state together; only query the original operation.

```json
{
  "toolName": "automation_get_schedule_draft",
  "effect": "read",
  "arguments": {
    "operationId": "the-original-write-uuid",
    "argumentsDigest": "the-original-64-hex-digest"
  }
}
```

The query also requires the original current grant and zero budgets. It returns the stored original audience; a new query's correlation fields cannot replace it. `not_found` is not permission to replay. `rejected` with `rate_limited`, `draft_capacity_reached`, or `original_operation_changed` reports that this call did not store a new proposal. `unknown` / `draft_save_unconfirmed` requires original query only.

Revocation, expiry or grant changes block new submissions and approval/dispatch from the old device source. Existing proposal and rule bodies remain retained locally in a source-check quarantine. The local owner can inspect that record even if the original remote grant no longer permits a query. This credential change does not erase the owner's retained draft or silently convert it to an unrelated private-conversation source.
