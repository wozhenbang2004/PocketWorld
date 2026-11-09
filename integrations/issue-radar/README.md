# PocketWorld remote inbox and Issue radar

This is an opt-in remote message system, not an autonomous GitHub contributor.
No inbound event may automatically create comments, claim Issues, start an Agent or grant a phone capability.

## Architecture

1. A Cloudflare Worker cron checks public GitHub issues every minute, detects newly seen unassigned issues and newly added contribution labels. The first scan establishes a baseline.
2. D1 stores events with monotonic sequence IDs and unique source keys. External services such as MergeAtlas may submit PR feedback and service alerts using a separate write secret.
3. PocketWorld reads the authenticated HTTPS API, durably records events on-device and shows native Android notifications and a unified inbox in its existing React UI.
4. An optional explicit foreground receiver checks roughly every 50 seconds while displaying an ongoing Android notification. Android JobScheduler independently requests periodic offline catch-up at the minimum supported interval (not exact scheduling).

This is polling, not FCM. It cannot guarantee second-level notifications when the system force-stops or kills the application. It does not rely on a phone public IP. Future system push should deliver only a hint to sync the same persistent inbox rather than bypassing validation.

## Deploy

Requires a Cloudflare account and D1 database. No VPS, DDNS, phone port mapping or ntfy server is needed.

From this directory:

    npm install
    npx wrangler login
    npx wrangler d1 create pocketworld-notices

Use the database ID returned by the CLI in the local wrangler.toml. The checked-in database ID placeholder is intentionally invalid. Apply:

    npx wrangler d1 execute pocketworld-notices --remote --file=./schema.sql

Generate two different random ASCII secrets, at least 32 characters. Store them as Worker secrets; do not commit them.

    npx wrangler secret put INBOX_TOKEN
    npx wrangler secret put INGEST_TOKEN
    npx wrangler secret put GITHUB_TOKEN
    npx wrangler deploy

GITHUB_TOKEN must have public repository read-only access; unauthenticated API limits are too low for minute-by-minute monitoring. The repository watch list is configured in wrangler.toml WATCH_REPOS.

In the PocketWorld notification page, enter the deployed Worker HTTPS root URL and INBOX_TOKEN. Save to enable OS-managed periodic offline catch-up. Optionally grant Android notifications and explicitly start the foreground receiver for faster scans. The receiver remains opt-in and has a persistent stop control.

## External event protocol

MergeAtlas publishes event notifications to POST /v1/events with these headers:

    Authorization: Bearer <INGEST_TOKEN>
    Content-Type: application/json

Example body:

    {
      "key": "github-pr:mybatis/mybatis-3#3883:review:123",
      "kind": "github_pr",
      "title": "MyBatis PR #3883 has a review",
      "body": "The maintainer requested a realistic scenario.",
      "origin": "mybatis/mybatis-3",
      "url": "https://github.com/mybatis/mybatis-3/pull/3883",
      "priority": "attention"
    }

A stable key prevents duplicate events on retries. GET /v1/events?after=0 uses INBOX_TOKEN and returns ascending sequences in pages of up to 100 events and a hasMore flag.

## Safety and limits

- Read and write auth use separate bearer secrets. The Android read token is encrypted with Android Keystore AES-GCM and not exposed in the frontend state.
- External event text and links are treated as untrusted. Events cannot run tasks, authorize tools or modify GitHub.
- No GitHub assignee does not mean a task is safe or available to claim. Verify Issue comments, linked PRs, maintainer responses and contribution rules before acting.
- Persistent device history is bounded. Disconnect explicitly removes local credentials and saved remote history. Device-local read/unread state is not synchronized to the cloud.
- Stored Worker events currently have no TTL. Implement retention before high-volume production use and avoid storing sensitive information.
- Deployment, working secrets and a signed tested APK are separate requirements. This code has not deployed an external Worker or created Firebase credentials.

## Checks

    npm test

The private PocketWorld main build workflow handles the bundled React frontend and Android APK. A successful source edit alone does not prove a working signed APK or real remote delivery.
