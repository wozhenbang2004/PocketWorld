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

## 可选 UnifiedPush 唤醒（源码候选，默认关闭）

此节不改变默认 `worker.mjs`，也不自动注册推送服务。手机原来的 HTTPS 拉取、事件游标与只读 Token 保持独立。候选路径是：先写入原事件表 → WebPush 加密的无正文 hint → 用户选定的 UnifiedPush 接收器 → PocketWorld SDK 回调 → Android JobService → 原 HTTPS 补收 → 设备 cursor 回执。即使推送重发或丢失，事件仍由原日志补收，不在推送回调执行 Agent。

### 操作者需要自己决定和配置

1. 选择可信 UnifiedPush 接收器和其推送服务。手机用户需自行安装/配置；本项目不创建账号、不代为登录。可以使用现有自托管服务，不要求 Firebase，但接收器及服务仍有网络/后台/维护成本。提供者可能看到端点、连接时间和加密流量，不能看到本路径的任务正文，因为该正文根本不通过推送传输。
2. 在已有私有 D1 上应用 `migrations/0002_optional_push.sql`。订阅 endpoint 和 auth 是敏感 capability，限制 D1 管理/备份导出，不能写进日志、截图或公开仓库。事件表并没有复制一份。
3. 显式选用 `src/worker-push.mjs` 为部署入口，并在自己维护的配置中设置 `PUSH_ENABLED="true"`、`PUSH_ALLOWED_HOSTS`（逗号分隔精确域名，最多 16 个，不接受 wildcard、localhost/IP 或跳转）。只允许实际选择的可信推送商域名。默认入口没有这些路由。
4. 自行生成/配置 VAPID P-256 键对：`PUSH_VAPID_PUBLIC_KEY` 为 87 字符 base64url 未压缩公钥，`PUSH_VAPID_PRIVATE_KEY` 为 43 字符 base64url 私钥，`PUSH_VAPID_SUBJECT` 为运营者 HTTPS 或 mailto 联系方式。私钥通过既有 secret 配置流程保存，绝不能进 Git/APK/前端。可使用所选库/可信 WebPush 工具的生成方法，不使用公开示例私钥。
5. 单独生成至少 32 位的 `PUSH_REGISTER_TOKEN`。它必须与 `INBOX_TOKEN`、`INGEST_TOKEN` 不同；只用于该服务的设备登记，不授权读取/写入事件。手机不把任何这类 Token 交给 distributor。SDK 提供临时备用接收器端点时，本应用不会自动登记它；用户可停用后明确选择新接收器。每台设备自行生成仅管理自己登记的随机 secret，服务端只保存其 SHA-256 摘要。
6. 发送库固定 `@block65/webcrypto-web-push@2.0.0`（MIT），声明为 optional dependency。默认入口不导入它。当前未安装/解析依赖、未生成新的依赖锁、未运行构建，不能直接当作已验证部署产物。启用前需在获准的环境完成不含测试的依赖锁和 Worker 打包；该库声明 Node >=22 的工具链要求，实际 Worker 使用 WebCrypto。
7. APK 操作者显式设置 Gradle 属性 `pocketworldUnifiedPush=true` 才编入 `org.unifiedpush.android:connector:3.3.5`（Apache-2.0）及 `src/unifiedPush/java`；否则使用无 SDK 的禁用占位源集。可选构建使用 compileSdk 36，默认仍是 35。现有 AGP 8.7.3/Kotlin 2.1.20 与新 SDK 的完整兼容、依赖锁及签名 APK 尚未构建核验；不要通过跳过 metadata 检查或关闭 dependency locking 掩盖问题，需要时单独升级经确认的构建工具链。
8. 手机通知设置中先连接原 HTTPS 收件箱，再明确选择已安装的接收器、输入专用登记 Token 并同意共享端点。仅显示“已登记”时还没有证明到达。随机 challenge 必须从真实推送链收到，接着原事件补收成功，界面才显示确认时间。该流程不是自动化测试，也没有在本轮替用户执行。

### 接口与失败恢复

- `GET /v1/push/config`、`POST /v1/push/register` 使用专用登记 Token。登记包含 `{deviceId,secret,subscription:{endpoint,keys:{p256dh,auth}}}`；最多 16 台，30 天租约，活跃客户端每天维护。返回登记结果但不返回 challenge。
- `POST /v1/push/ack`、`DELETE /v1/push/device` 使用该设备 secret，body 包含 deviceId；ack 可带从推送收到的 challenge 和实际持久化 cursor。不会把 HTTP 2xx 当用户送达回执。
- cron 延续已有一分钟触发，扫描待确认/落后 cursor 的设备；每批最多 8 台、4 并发，指数退避至 1 小时，TTL 1 小时、normal urgency。new event POST 也请求一次有界发送。端点 404/410 后移除；缺失登记由客户端重新登记。单个提供者接受不代表收到，只有设备成功补收回执才停止对应积压唤醒。
- Android 31+ 收到合格 hint 后申请 expedited Job；无配额则保留普通持久 network Job。旧 Android、Doze、限制后台、force-stop 或接收器网络故障仍可能延迟，没有硬实时 SLA。普通 foreground 检测和周期 Job 没有被包装成系统推送。
- 停用先删除本机有效身份和 SDK 订阅，再尽力删除服务端登记；断网时远端删除未确认，运营者可删除该设备记录，或等待租约到期。更换连接会先退订旧身份，不会把新服务凭据用于旧地址。
- 推送数据永远只有 kind/随机 instance/一次性 challenge，没有任务正文、标题、URL 或工具命令。系统通知的点击、归档、已读和原任务审批继续走原中心路径。

### 当前证据

已读固定来源和 API，已完成候选源码与 `node --check` 静态语法检查。没有新增或执行测试，没有安装依赖、部署 Worker、设置 secret、创建账号、修改手机权限或运行真机。完整 APK/Worker 构建与真实注册送达仍待获准环境及操作者配置，不能称已上线或已验证可靠推送。
