# PR #177 — consolidated triage of two independent reviews

Scope: draft PR #177 (`port/whatsapp-2.26.46.82`), first reviewed at `f003620e`. Mandatory hosts:
the control, WhatsApp 2.26.38.74 (263807433), and the target, 2.26.46.82 (264608200). The
intermediate 2.26.46.71 was not dumped and nothing here applies to it.

No finding from either review was accepted on trust. Each was checked against the module code,
against the consumers of the resolved members, and against the original APKs with `dexdump` and
`aapt2` (build-tools 36.0.0), independently of DexKit. Classes:

- **Confirmed**: reproduced in code and/or the APKs; fixed in this PR.
- **Partially confirmed**: the mechanism is real but the stated impact is narrower; hardened.
- **Rejected**: not reproduced.
- **Needs evidence**: cannot be decided off the device; runtime checks are named.

Obfuscated names below are the control's first, then the target's.

## Second technical review

| ID | Finding | Class | Evidence | Resolution |
|---|---|---|---|---|
| P1 | Message-key fallback takes the last field of the key type | Confirmed | Both hosts declare a mutable `A0I` plus a final key (`A0i`/`A0j`) of the same type. Field order alone decided. | `MessageKeyContract`: evidence must be unique and an instance field of the message hierarchy. The legacy path needs exactly one final field. Cache schema 3 drops old picks. 6 unit tests. |
| P1 | Separate Groups installs partially | Confirmed | Each `hookTabX` hooked on its own; a later failure left earlier hooks live, and `hookTabList` swallowed its failure. | `HookTransaction`: all-or-nothing, with the badge as an optional sub-transaction. Failures propagate. Tests cover rollback order, failing undo, adopt and failure mid-install. |
| P1 | Tablet conversation returns a stale JID | Confirmed | `cachedActivityHash` and a global "last constructed delegate" returned the first opened chat after switching chats in the HomeActivity pane. | JID resolved on every call from the visible surface. Intent JIDs are kept per activity (`onNewIntent` updates them). HomeActivity fails closed. |
| P1 | Diagnostic events lost by a race | Confirmed | `attempted` blocked retries. Events before `run` was set were dropped. The session bound lazily from the first feature constructor. | Bound once on the loader thread before any feature runs. Events are buffered and replayed (512 max, drops counted). One retry on transient IPC failure. Tests include concurrent emitters. |
| P1 | Catalog covers only part of the loader | Confirmed | 39 of 70 features. Failures of the rest never reached the aggregate. | `FeatureManifest` (70, equal to the loader order by a test). Every registered feature's failure degrades the result. Snapshots without a loader completion marker are UNKNOWN; a timeout gives DEGRADED. |
| P2 | `enabled()` defaults to true | Confirmed | Features without a modelled gate looked enabled and "not exercised". | Tri-state enablement. 18 leading preference gates are pinned to the sources by a test. Everything else reports `ENABLEMENT_UNKNOWN`. |
| P2 | "Triggered" means callback entry | Confirmed | `triggered` fired before the hook decided anything. | `reached` and `acted:<path>` are separate. Required cases need their own path after arming: HideSeen `direct-read-suppressed`, AntiRevoke `message-revoke-blocked`. |
| P2 | Resolver cache ignores host identity | Partially confirmed | It was keyed only on versionCode and update time, so the same versionCode with other splits kept stale descriptors. Nothing showed this on the two hosts. | `CacheIdentity`: base and split paths, sizes and mtimes. Mismatch clears the cache. Tests. |
| P2 | Reverse string map hard-codes `0x7f12` | Partially confirmed | `aapt2` shows `string` is type 0x12 in both hosts, so it is correct today but fragile. | Package and type ids are read from the resource table. |
| P2 | Official-record key is self-referential | Confirmed (latent) | `moduleSha256` hashed the whole APK, including `assets/validated_builds.json`. The registry is empty, so there was no impact yet. | `moduleCodeSha256` excludes `META-INF/*` and the catalog. Catalog schema 3. Test. |

## First independent review (own findings)

| ID | Finding | Class | Resolution |
|---|---|---|---|
| C-1 | MediaPreview video row anchor `frame_visibility_serial_worker` is absent in the target | Confirmed | The assertion "thumb view type is not correct" occurs once in each host (`X.Dr2`, `X.Kza`). It is now the primary strategy, with the old anchor as fallback. Covered by the census and by `OfflineDexAudit`. |
| C-2 | Transcoder hook is inert on the control and aborts MediaQuality on the target | Confirmed | On the control the anchor resolves only to `BotVideoDownloader` (AI bot), and it is absent in the target. Hook removed; video and image are independent transactions. |
| C-3/C-4 | Conversation JID fallbacks and graph walk | Confirmed | Fixed by the same rewrite as P1 (tablet). The delegate fields `A0O`/`A0X`/`A0Y` are equivalent in both hosts. |
| C-11 | Chat-list row JID read from `A00` | Confirmed (proven misclassification) | `A00` is a chat-info object (`0DY`/`0w9`) with no `getServer`, so such rows appeared in both tabs. `ChatListClassifier` uses `getJid()`. 4 tests. |
| C-12 | Legacy `com.whatsapp.ConversationFragment` branch | Partially confirmed | Dead in both audited hosts. Kept, guarded and commented, for older supported hosts. |
| C-13 | Media Library row opens the "current" chat | Confirmed | It opens the tapped message's own chat (`ref.chatJid`). |
| R-1 | AB-prop overrides depend on `Others` reaching `hookProps()` | Confirmed (structural) | `HostProps` is a shared idempotent service. HideSeen, MediaQuality and CustomToolbar install it themselves or report degraded. Whether WhatsApp reads 19148 at runtime is **needs evidence**: the flag appears only in the registry table `X.0Dx`. |
| R-2 | Core probe on the loader path | Confirmed | Runs after installation on a daemon thread. One BEGIN IPC per host start remains (documented). |
| R-3 | OutOfChatPlaybackScope cancels a frame | Needs evidence | It cancels only when hiding a visible strip; check on a device. |
| C-14 | Offline tools not portable | Confirmed | UTF-8 reads, `as_posix` sort, `os.pathsep` classpath, test import path. |

## Found while expanding the offline audit

The census (`tools/compatibility/anchor_census.py`) covers all 172 string anchors that
`Unobfuscator` and `HostDexContracts` pass to string matchers, in both APKs. 11 are absent in
the control and 14 in the target. Each anchor that changed class between the builds was checked:

| Anchor | Control → target | Class | Resolution |
|---|---|---|---|
| `outgoing-launch/cm-null-contact` (CallType) | 3 methods → absent | Confirmed (new) | On the control, "first match" could pick the String-argument sibling `CdZ` or a click handler instead of the launcher `CdY`. The `(Context, WaContact, int, boolean)` + `singletonList` shape is unique in both hosts (`1Xw.CdY`, `F8L.DLZ`, verified in the dexdump output), so it is now primary. The string is a signature-checked fallback. |
| `FMessageUtil/getOriginalMessageKeyIfEdited` | 1 → absent | Needs evidence | The structural fallback fails closed when ambiguous. It is DexKit-only; the runtime core probe resolves it on the device. |
| `statusTilesEnabled` | 1 → absent | Rejected as a defect | Fallback only. The primary `NewsletterHeaderDataItem(enableExploreButton=` is unique in the target. |
| `CONTACTS_FILTER`, `Unknown JsonField` | count changes | No action | Counts changed, but both still resolve. |
| 11 anchors absent in both | — | Out of scope | Fallbacks for older hosts; they are not a regression between control and target. |

## Evidence levels (what is and is not claimed)

- **L0, independent census** (both APKs, committed fixture, parameterized tests, a live re-run
  when `WAE_DEXDUMP`/`WAE_APK_*` are set): anchor presence and uniqueness only. It does not run
  the resolvers.
- **L1, DexKit offline** (`OfflineDexAudit`): the HostDexContracts queries, now including the
  video row. This needs DexKit 2.2.0 JVM classes and the native library. It was **not re-run in
  this round**; the `offline-*.json` reports are the earlier run.
- **Runtime**: a per-feature loaded/installed/error/degraded record, resolver misses attributed
  to the loading feature, the loader marker, the core probe, and `acted` paths. The behaviour
  does not depend on this instrumentation. Recording is off when there is no session, and
  feature hooks only report; they never branch on diagnostics.
- **Not available**: L2–L5 on a device, physical two-endpoint receipts, a tablet run on both
  hosts, the 19148 runtime read, out-of-chat strip behaviour. **Full compatibility with
  2.26.46.82 is not declared.**
