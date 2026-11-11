# Remote-control continuation: bounded LAN address discovery

Source checkpoint: `50ceebc077517e937de5007c4302d9172bf132be`, 2026-11-11.
This note covers this discovery increment, not completion of every remote-control goal.

## Intent and existing capabilities

The current assignment asks for genuine phone pictures/input, LAN discovery and cross-network
computer control/desktop agents without presenting saved state as a live connection. The
parallel Phone Use conversation was read before changing code; this increment does not replace
its key, notification, app-recovery, calendar or observation execution paths.

The inspected main already has `OwnerPhoneControlSession` and `PhoneControlWorkspace`: real
per-observation images and single-step human clicks, swipes, node input, application launch and
system navigation within the original reviewed run. It explicitly reports `liveVideo:false`.
The Windows desktop viewer already uses WebRTC for pictures and original mutually authenticated
device channels for input. Control relay and media connectivity are separate. Those implementations
were read, not exercised or newly implemented by this increment.

The concrete missing path addressed here is finding a changed LAN address without replacing
the original paired identity. New-device pairing still uses the existing short-lived invitation.

## Reuse and quality floor

* ADOPT Android `NsdManager` DNS-SD, rather than implementing multicast DNS or scanning IP ranges.
  The official [NSD guide](https://developer.android.com/develop/connectivity/wifi/use-nsd)
  was read for registration callbacks, service resolution/loss, renamed registrations and lifecycle
  cleanup. The [API reference](https://developer.android.com/reference/android/net/nsd/NsdManager)
  documents multicast-lock requirements on older Android releases. The normal
  `CHANGE_WIFI_MULTICAST_STATE` permission supports a lock acquired only during this explicit window.
  Current source targets SDK 35 (minimum SDK 30). A future SDK 37 target requires a separate
  Android local-network permission/system-picker review; this change does not request that broad
  permission. No new account, network service, device setting, firewall rule or actual permission is enabled
  by this source change.
* REFERENCE [RustDesk LAN discovery](https://github.com/rustdesk/rustdesk/blob/master/src/lan.rs),
  read at current master on 2026-11-11: explicit discovery permission and bounded packet reception
  are relevant; its custom UDP/protobuf wire format and publication of machine/user identifiers
  are not adopted. No RustDesk source was copied and its service was not installed.
* ADOPT existing `forms-library` Accordion/Checkbox/Select/Button, original device review window,
  exact trust identity and mTLS connection lifecycle. No new visual component family or separate
  task runner. Discovery remains low-frequency setup, not a competing primary action.

The observable floor is separate evidence for address discovery, identity authentication,
connection state, permission and task outcome. A DNS-SD entry satisfies only the first item.
Source review supports that separation; current visual/platform behavior remains unverified.

## Owner journey and boundaries

1. In **我的设备 → 本机接收 → 临时查找局域网地址**, explicitly approve a 60-second search.
2. Optionally advertise this phone after its existing receiver has already been enabled.
   The other Android phone must explicitly enable the same temporary broadcast. Defaults are off.
3. The system publishes a random per-window service name, control port and bulk port under
   `_pocketworld._tcp.`. No persistent device identity, hostname, username, certificate, invitation
   token, grant or task content is included. Other LAN users can nevertheless see the service.
4. At most 32 service records are retained at once and resolved serially and at most 16 deduplicated endpoint
   candidates are displayed. Loss, expiry and close invalidate their opaque handles. IPv6 scoped
   link-local, loopback, unspecified and multicast addresses are excluded; an empty result does not
   establish absence. Guest Wi-Fi isolation, VPNs and OS restrictions can prevent discovery.
5. Explicitly select an existing paired identity, inspect its full pinned fingerprint and the
   untrusted endpoint, then confirm. The original peer instance, authorization epoch, local identity,
   discovery session and candidate must still match. Already-online peers are not replaced.
6. Confirmation saves only native local `pairedLan` address configuration, with no fabricated
   pairing ports, and starts the existing exact-certificate mTLS connection. The address is not
   itself verified by discovery; a wrong candidate fails authentication and may require restoring
   the original endpoint from that device's invitation. Control and bulk lanes both use the selected
   address while retaining existing identity/authorization/operation guards. No operation is replayed.

Deadline uses monotonic time. Back/page replacement, native presentation invalidation, background,
receiver shutdown, explicit stop and expiry disable candidates and request system cleanup.
Discovery and registration failures stay visible. Stop/unregister retries are bounded; a missing
system acknowledgment remains `stopPending`, not successful withdrawal. Another native discovery
cannot replace pending cleanup. A scan never silently resumes on foreground return. The web page
polls only the local snapshot, not peer capability requests, and bounds this polling window.
The native owner retains each session before making any system request. If startup fails after
discovery was requested, its closed session and pending cleanup remain visible in the same window;
the bridge reports only a startup request, not successful discovery or registration.

## Remaining work, deliberately not declared complete

* Companion does not yet advertise this DNS-SD service. Desktop pairing/address updates continue
  through its existing invitation workflow. Cross-platform discovery needs a separately integrated
  mature OS/library adapter and its packaging; no optional dependency was installed here.
* Phone manual control remains single-observation pictures, not continuous video streaming.
* Cross-network desktop media still requires owner-configured ICE services and a reachable original
  device control path. WSS control relay is not TURN and does not by itself deliver desktop video.
  No hosted relay, TURN account, public endpoint or subscription was enabled.
* Windows remains limited by its existing capture/interactive-session support. This work does not
  establish macOS/Linux desktop execution, arbitrary NAT traversal, or OEM Android compatibility.

## Evidence

No tests were added or run. No device interaction, dependencies, local build or service activation
was performed. Static cross-review covered original identity, candidate lifetime, UI/native
invalidation, unchanged permission authority and separation of discovery from online state.
Java/Kotlin/TSX compilation and Android DNS-SD behavior require the normal authorized build and
later real owner experience. File/diff checks are not runtime or visual acceptance.
