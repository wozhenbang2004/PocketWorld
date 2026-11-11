# Gmail connector for PocketWorld

This is a real Gmail API adapter for PocketWorld's existing **native local MCP** path, not a simulated mailbox or Google's preview-only Gmail MCP endpoint. It provides account verification, paginated search, bounded message reading, plain-text sending and reconciliation of uncertain sends.

**Status:** source implemented; Python syntax reviewed only. Dependencies have not been installed, OAuth has not run, no mailbox has been read, no message has been sent, and no device/runtime journey has been verified. Adding these files does not connect a mailbox.

## Prerequisites and explicit owner setup

- POSIX Python 3.10+ on the actual selected runtime. `fcntl`, `getuid` and `O_NOFOLLOW` are used deliberately; Windows Python is not supported. A prepared Linux environment is required.
- Runtime dependencies from `requirements.txt`. Direct versions are pinned, but transitive dependencies are not yet locked or installed. Do not describe this as a reproducibly packaged runtime.
- Your own Google Cloud **Desktop OAuth client**, Gmail API enabled, and the correct consent-screen/account access. This is the regular Gmail API, not the Workspace MCP Developer Preview. A public release may require Google's OAuth verification. No OAuth client identity is bundled or borrowed from another app.
- An owner-only stable state directory outside Git, the source project, frozen execution copies, logs and backups. The adapter stores the refresh credential as a mode-0600 JSON file in that mode-0700 directory. **It is not encrypted at rest and does not use Android's encrypted credential vault.** Do not use it on a shared or untrusted runtime. The current local runtime is not an OS security sandbox; other same-UID code may read its files.
- An interactive terminal/browser on that same authorized runtime to complete the separate owner login. No MCP tool starts login, registers an account or accepts a model-provided token.

The current PocketWorld native MCP dependency preparer handles npm/pnpm, **not pip**. This connector therefore requires a separately prepared Python interpreter with these libraries available to the actual `python3` used by the host. A venv on another computer or a developer laptop is not proof that the Android runtime has them. Do not place tokens into source files or add a fallback that copies them into the task workspace. If the chosen runtime cannot provide stable private storage and an owner login path, mailbox activation is blocked there; do not claim it is connected.

The following commands are owner setup instructions, not commands executed during this implementation. Review the package sources and storage destination before installing or authorizing:

```sh
python3 -m pip install -r connectors/gmail/requirements.txt
python3 connectors/gmail/server.py authorize \
  --account owner@example.com \
  --state-dir /OWNER_PRIVATE_STABLE_DIRECTORY/gmail \
  --client /OWNER_PRIVATE_DIRECTORY/client_secret.json
```

The client JSON must be an owner-only file. Use your real account and private paths, not these placeholders. OAuth uses Google's library, a loopback callback and PKCE. Account identity is checked against Gmail before saving. The owner must type the exact account to proceed. Read-only is the default; scopes are `gmail.readonly` and, only when explicitly selected, `gmail.send`. No broad `mail.google.com` or delete/modify scope is requested.

For sending, explicitly add `--enable-send` to the owner authorization command **and** to the later server configuration. To renew an expired/revoked authorization for the same account, run `authorize ... --replace` yourself, with the desired scopes. Changing accounts requires a separate state directory and a newly reviewed installation. Access tokens are refreshed using the existing grant before a request; `invalid_grant` or denied scopes require owner reauthorization, never an automatic login.

## Install through the actual native MCP path

1. Include `connectors/gmail/server.py` in the source project and prepare the actual selected Python runtime. Secrets and the outbox ledger remain outside that project.
2. In the existing original-project native MCP preparation/review, select the current checkout/source digest, `python3`, protocol `2025-11-25`, and the public argv below. See `pocketworld-mcp.example.json` for the configuration shape. The checkout and source identities must come from the real project; the example does not mint them.
3. Review the frozen source, runtime, command, stable storage location and discovered tools. Discovery lists schemas without reading email or starting OAuth. Installation remains disabled and ungranted until the owner's separate decisions.
4. Grant only the required tools, then select that installation in the task's real scope. A schema in the directory is not a task grant or a successful Gmail call.

```json
["connectors/gmail/server.py", "serve", "--account", "owner@example.com", "--state-dir", "/OWNER_PRIVATE_STABLE_DIRECTORY/gmail"]
```

Appending `--enable-send` changes the frozen command and discovered tool set. It must go through a new discovery/install/permission review, not a hidden switch in an existing task. The public argv contains an account identifier and paths, never client secrets, access/refresh tokens or passwords. The remote-address/config-file form in the general MCP page is not a substitute for this native local-program review.

## Tools and sending boundary

| Tool | Real behavior |
| --- | --- |
| `gmail_account` | Verifies the authenticated account matches the owner-selected account. |
| `gmail_search` | Uses Gmail search syntax; returns at most 20 message/thread references and an explicit next-page token. References are not message bodies. |
| `gmail_read` | Reads one accurate message ID; returns bounded text/HTML as untrusted data and attachment metadata only. Never renders HTML, loads remote images or downloads attachments. Truncation is explicit. |
| `gmail_send` | Present only with `--enable-send`. Sends one new plain-text email, with explicit account, To/Cc/Bcc, subject, full body and stable operation ID. No attachment, reply-all or implicit-recipient behavior. |
| `gmail_send_status` | Reads the original durable ledger; for an unknown send, looks for its exact Message-ID in Gmail Sent. It never sends again. |

In PocketWorld, `PluginRules` classifies all MCP calls as writes. More importantly, local MCP execution carries `nativeProgram` in `HostToolSupervisor`'s precise approval capability; `ToolApprovalPermit.protectedApproval` does not allow an AI review or `full_access` permit to substitute for that original native owner review. The review binds task/run, installation/source/runtime identity and all concrete tool arguments. The send call exposes the complete recipient lists, account, subject and body for that review, not just an opaque draft ID or model-supplied `confirmed=true`. Sensitive data, commitments and other high-impact content must be included in the owner's concrete approval. Connection or tool enablement alone is not permission to send.

This server does not claim to cryptographically authenticate arbitrary external MCP clients as PocketWorld. Do not deploy it behind a public listener or attach its sending mode to another client without equivalent concrete owner approval. It exposes stdio only.

Before sending, the adapter commits an `unknown` record with an exact-content digest and generated RFC Message-ID to SQLite with `synchronous=FULL`. Only then does it call Gmail. Google's client uses `num_retries=0`; the underlying requests transport also has zero retries, no redirects and no 401 refresh/replay. This avoids httplib2's implicit stale-connection POST retry. A timeout, cancellation/process loss, malformed receipt or local receipt-save failure is not treated as permission to retry. The same operation ID or exact message content is not dispatched again. `sent` means Gmail returned a message ID or the exact message was found in Sent, **not** that the recipient received/read it.

The duplicate-content guard is deliberately conservative: an identical account, ordered recipient lists, subject and body cannot be sent again through a new operation ID, even later. A legitimate repeat requires a separate owner-maintained workflow; the model has no deletion/reset/retry tool. No match in Sent is still `unknown`, not proof of non-delivery. Existing duplicate records and rejected sends are never silently cleared. Cancellation after dispatch cannot unsend an email. Keep the ledger durable; replacing the state directory would lose this protection and must not be used to retry an uncertain send.

## Dependencies and reuse

- [MCP Python SDK v1.26.0](https://github.com/modelcontextprotocol/python-sdk/tree/v1.26.0), MIT: standard stdio server, exact bounded tool schemas and 2025-11-25 protocol. V1 is selected intentionally for the native host; SDK v2 is not assumed compatible.
- [google-api-python-client v2.202.0](https://github.com/googleapis/google-api-python-client/tree/v2.202.0), Apache-2.0: Gmail discovery definitions and request/response construction. Requests use its bundled static discovery document.
- [google-auth-oauthlib v1.2.4](https://github.com/googleapis/google-auth-library-python-oauthlib/tree/v1.2.4), Apache-2.0: user OAuth flow, PKCE and loopback callback; it depends on Google's auth and requests libraries. Its source later moved to google-cloud-python; this exact tag is the inspected API.
- Google's `AuthorizedSession`, requests' `HTTPAdapter(max_retries=0)` and `httplib2.Response` are used as client transport glue. No OAuth protocol or MIME encoder is reimplemented; Python's standard email library builds the plain-text message.

Only the adapter glue is new. Dependencies are declared, not vendored. Their licenses/notice obligations remain with their distributions. No new test files, test runner, dependency installation, account creation, deployment or email transmission was performed for this change.
