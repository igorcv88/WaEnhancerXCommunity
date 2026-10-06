# Unified runtime review — 2026-10-06

Reviewed default branch: `master` at `a951ab07b9ad0a74258c69bbe4dcd0a5a02a848a`, after PR #65 (`d93e9071`) and PR #64 (`a951ab07`) merged. Follow-up branch: `fix/unified-runtime-review`.

## Findings and changes

| Finding | Result |
|---|---|
| PR #65 unresolved status-reply comment | Confirmed. An outgoing status reply targets its author's contact JID. PR #66 follow-up binds release to the outgoing job ID and its resolved original status key, validates the exact incoming status ID and matching recipient PN/LID, and queues only that quoted item. Viewer state cannot select the receipt. Publishing one's own status does not release someone else's receipt. |
| Manual button setting gated status tracking | Fixed. `seentick=0` no longer disables the context needed for independently enabled blue-on-reply. |
| Raw LID getter checked `phoneJid` instead of `userJid` | Fixed. LID matching remains available when PN mapping is unavailable; a missing LID no longer reaches reflection merely because PN exists. |
| PR #64 unresolved fallback-opacity comment | Already fixed by its final commit `8bdfbe9e`. `GlassMaterialDrawable` uses `material.withoutOptics()`, including the 72% floor. Keep that implementation. |
| Glass capture omitted whole bound views | Fixed. Excluding a toolbar/composer/panel `ViewGroup` omitted its native children too. Recursively draw native content and suppress material backgrounds through the existing provider capture gate. Remove obsolete per-view exclusion bookkeeping. Independent `GlassSurface` rendering hosts remain excluded. |
| Lifecycle/contact listener installation raced with dispatch | Fixed. Use the tested `FeatureCallbacks` snapshot registry and isolate failing feature callbacks. Protect synchronized-map iteration in `ActivityStateRegistry`. |
| Status menu registration raced with iteration | Fixed. Iterate a synchronized snapshot; move both `StatusDownload` registrations to the registration API. Isolate individual menu-build and click failures. |
| Receipt DB queries could publish stale cache state after authorization | Fixed. Serialize receipt reads, cache updates and writes on the same history instance. Require one successful database update before reporting authorization success. A delayed hiding callback no longer overwrites explicit authorization; explicit rollback remains available. |
| Existing history upgrades/downgrades dropped user tables | Fixed for known version 4–6 upgrades: preserve `MessageHistory`, archive the complete original receipt table, merge duplicate receipt bookkeeping with `MAX(viewed)`, recreate indexes and validate copied logical-row count in SQLiteOpenHelper's transaction. Unknown row-ID schemas below version 4 and downgrades fail without erasing their source. |

`viewed=true` remains explicit receipt-release authorization, not server acknowledgment. Automatic status release now uses outgoing quoted-status metadata rather than visible context. Missing outgoing/quoted messages, mismatched IDs, own statuses or recipient mismatch cannot release a visible status. The hook uses the host job `id` field, the existing original-key resolver and host key lookup; their runtime compatibility needs device acceptance. An unavailable outgoing identity skips automatic release for that job.

## Review coverage

Reviewed both PR discussions and inline threads, inventoried all 40 changed paths across the merged PRs, and inspected the shared entrypoint, loader/lazy-install path, activity/contact/status registries, manifest, preference schema/provider/client, resolver cache, history storage, backup/diagnostics contracts and new feature registration. The merged baseline has 295 Java sources and no Kotlin source. This is a source and architecture review, not an exhaustive runtime proof of every feature or WhatsApp build.

Application identity, settings files/keys, public/private configuration boundaries, default-off glass adapters, existing receipt-release ordering, one `assets/xposed_init` entrypoint and manual workflows remain intact. The baseline cache still uses host-private SharedPreferences; status menus still use `StatusItemWaex`/`MenuStatusListener`; history still uses SQLite version 7. No Yuki/KSP, DataStore or Room adoption is claimed by this stabilization PR.

## Validation

- **216 JUnit tests passed across 27 test classes**, compiled from the current source. Coverage includes settings/schema/backup allowlists, encrypted-backup corruption, semantic themes/glass policy/window ordering, update digest/version policy, diagnostics/redaction, receipt policy/release, media payloads, ten status-routing cases (including delayed same-author A/B, unresolved/wrong quotes and immutable worker selection) and three new snapshot/concurrent callback cases.
- This offline harness used an Android 36 framework jar (including hidden APIs; corrected below) and JSON-java `20260814` source at `6c140480797ad70a41df84bc8ebab405ca44656b`, plus cached JUnit 4.13.2-SNAPSHOT/Hamcrest. Generated BuildConfig values and a minimal Android `PackageManager.NameNotFoundException` runtime shim allowed pure updater/diagnostic tests to load. No APK package/signature checks were exercised by that shim.
- **Five SQLite tests passed** via `python3 tools/test_message_history_migration.py`. They execute production migration SQL with explicit transactions and cover unchanged edit history, original receipt archive, authorization-preserving deduplication/type separation, index ownership, late-failure rollback and archive-name collision.
- Partial Java 17/API 36 checks passed for receipt/compatibility classes, actual modified history/lifecycle/status/contact-listener implementations and glass renderer/capture/adapters. External AndroidX/Material/Kotlin/OkHttp and unrelated module collaborators use signature stubs; unused Lombok imports were stripped from a scratch copy of history for that check. These are API/type checks, not a full build. The one-line raw-JID change and unchanged-body `StatusDownload` registration call replacements were inspected in source; no full compilation of their enclosing classes is claimed.
- All manifest/resource XML parsed; `git diff --check` passed. No workflow dispatched and no APK installed.
- Full `:app:assembleWhatsappDebug :app:testWhatsappDebugUnitTest --no-daemon` failed before project configuration at the Gradle 8.14.5 distribution download: `java.net.SocketException: Network is unreachable`. Only Java 17 is installed; the project requires JDK 21. A full build baseline has **not** been established.

## Remaining work and acceptance

The five foundation migrations remain pending behind the full build baseline required by the handoff. Continue Kotlin UI first, then YukiHookAPI/KavaRef/KSP with preference transport, DataStore resolver cache and coordinated status/history-provider consumers. Keep translation, system emoji, public recording storage and remaining compatibility ports separate.

Preference transport review found pre-existing semantics to address in that stage: `ProviderEditor` sends mutations during `put*` rather than at commit/apply, reports local commit success without provider acknowledgment, and hydration can discard a final notification inside its 500 ms throttle window. Preserve the existing schema/caller boundary when correcting those semantics. Do not silently replace the provider with an unrestricted RemotePreferences contract.

History versions below 4 require a validated host-account row-ID mapping before their old edits can become active under message keys. Their files are retained, but the existing `getInstance()` recovery path can use an empty in-memory DB until that migration exists. A name collision or unexpected version 4–6 schema similarly rolls back and retains the source. These paths require Android/device migration acceptance; SQLite tests alone do not validate SQLiteOpenHelper integration or the fallback UI. Tasker history's currently dormant version-1 destructive upgrade stub also needs replacement before a future schema bump.

Physical acceptance on WhatsApp 2.26.33.76: ordinary/multiple/group/notification replies with Hide Delivered + blue-on-reply; Hide Read without Hide Delivered; manual release; status reply with manual buttons disabled, advance A to B from the same author before job execution, switched authors, closed/paused viewer, PN/LID mapping and posting one's own status. Verify status downloads and other menu providers continue after a failing adapter. For glass, test native text/icons/children in toolbars/composer/panels, nested surfaces, recycled bubbles, popup-over-dialog, fallback contrast, ripples and S25 Ultra frame/thermal behavior. None has new device evidence in this session.

Upstream refresh: `Dev4Mod/WaEnhancer/master` remains `749c3cae4a1f1cffc2572a4a2365517ba6ce0322`; **zero** newer commits were available. The distinct X source remains unavailable as recorded in the earlier audit.

## Public SDK build correction — 2026-10-06

After #66 merged as `39630849`, the user's full runner build reached Java compilation and failed on `SharedGlassBackdrop` calling `Choreographer.getFrameTimeNanos()`. That method is hidden from the public SDK. Earlier partial checks used an Android framework jar that exposed hidden members; those checks did not validate the public SDK surface. This corrects the scope of their reported API validation.

Use a UI-thread `GlassFrameClock` shared across window providers. One outstanding public `postFrameCallback` receives `doFrame(frameTimeNanos)` and publishes the token; repeated pre-draw/lower-window preparations share it. Pre-draw still captures and allocates the budget once per token. The first traversal uses the existing bootstrap/fallback until a callback arrives; after an idle gap, the first traversal may reuse the previous backdrop until the next token. Callbacks neither invalidate views nor self-schedule, avoiding an idle animation loop and provider retention. Software capture retains its 100 ms limit.

Three new clock tests passed: shared token/callback deduplication, no idle callback loop, and retry after scheduling failure. The glass renderer, backdrop, adapters and settings partial compilation passed against the public API 36 stub jar from `Sable/android-platforms` at `1e98db1a199e8f7f85541af26bfc27019501b132`; `javap` confirms that jar excludes `getFrameTimeNanos`. Unavailable third-party/module collaborators still use signature stubs. No full local APK or device success is claimed. The supplied runner output establishes a failed full build, not a completed baseline. No workflow was dispatched.
