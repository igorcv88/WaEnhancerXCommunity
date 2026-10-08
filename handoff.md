# Agent handoff

This is shared working state for Codex, Claude, GPT, Antigravity, and subsequent agents. Read `AGENTS.md` and `ARCHITECTURE.md` first. Update this document after each task; preserve decisions and evidence instead of treating earlier plans as completed work.

## Current task — 2026-10-06

Repository: https://github.com/igorcv88/WaEnhancerXCommunity

Unified base: `master` at `a951ab07b9ad0a74258c69bbe4dcd0a5a02a848a`. PR #65 merged as `d93e907133a4b753395cd01839ebf87b836c41be`; PR #64 merged as `a951ab07`. Their receipt/compatibility/glass changes are all carried by the current base. PR #66 merged as `39630849574457e4fadd9966e5b99d4797430c22`. Current working branch: `fix/public-glass-frame-clock`, based on that merged master; published as ready-for-review PR #67. The original #65 implementation was `47ba889050551df0f1128be0b2d70cb9b0e5410c`, followed by `b4bfe43d`.

The user now authorizes the staged upstream migrations and asked to review the unified default branch first, including both merged PRs' review comments. The required full build baseline is currently blocked at the Gradle distribution download and missing JDK 21. Implemented stabilization is documented below; framework migrations have not been represented as complete.

The user confirmed Community `v0.0.8`, ordinary WhatsApp `2.26.33.76`. Initial report said Hide Delivered was off; **the user corrected this: Hide Delivered was enabled**. Correct target behavior: receipts remain hidden before replying, but Send Blue Ticks upon Reply releases delivered/read state when the user sends a reply. Manual Send Blue Tick / Mark Viewed currently releases both, according to the user's two-phone comparison. Treat that as reported device evidence, not a test of this branch.

User decisions:

- Earlier #65 scope: compatible fixes and receipt correction. Current scope: unified runtime review followed by staged Kotlin/UI, Yuki/KavaRef/KSP, preference transport, DataStore and status/history migrations; translation/system emoji/public recordings remain separate features.
- Include all migrations/pending changes here and provide a prompt to start migration in a new instance.
- English reasoning/documentation/replies; ask for missing facts or user-dependent choices.
- Reuse related open PRs; create PRs ready for review, never draft. Workflows stay manual.

Earlier #65 open PR inventory: #64 `feat/app-wide-liquid-glass` at `8bdfbe9e1e583ebf3951cc16e8f56d717d68e5dc`, plus dependency PRs #63, #62, #61, #55, #54, #43. None covers receipts. #64 has since merged. The current review includes its rendering/capture code and comments; new device rendering evidence is still absent. Refreshed open inventory contains dependency PRs #63, #62, #61, #55, #54 and #43, with no related open stabilization/foundation PR.

## Receipt diagnosis and implementation

Source inspection of both the base and `v0.0.8` found:

1. `SeenTick.sendBlueTickMsg` queued an explicitly flagged read job before updating pending hidden-message rows to `viewed=true`. A worker could reach downstream guards before authorization was visible.
2. `HideSeen.hookEnforceHiding`, the direct incoming-message guard added in the previous upstream port, checked privacy flags without honoring a previously authorized/viewed record.
3. The modern dispatch hook suppressed an entire dispatch based on any unviewed hidden row or Hide Seen setting. The protocol hook also returned null for any old unviewed row, independent of receipt type. These broad rules could suppress delivery even when only Hide Read was selected.
4. Outgoing-job discovery used `Contains` for SendE2EMessageJob. Reply handling could route a chat reply into the visible status branch based on stale `currentScreen`.

Changes merged through #65:

- `ReceiptRelease.enqueue` publishes authorization before enqueueing, restores pending flags on authorization/enqueue failure, and preserves rollback errors as suppressed exceptions. `SeenTick` requires a successful DB authorization update before invoking the queue. The existing job `blue_on_reply` marker remains in place.
- The direct guard, dispatch gate, and protocol gate honor per-message authorization. Old unviewed rows are bookkeeping, not an unconditional command to cancel all receipt kinds. Read-specific hiding stays at read job/direct/protocol gates; the broad dispatch gate uses effective Hide Delivered (including custom privacy/ghost mode).
- Protocol bookkeeping records a hidden read only when it actually transforms a read receipt, or when Hide Delivered is active.
- SendE2EMessageJob discovery uses upstream's `EndsWith`, checks the instance, and uses the outgoing destination to choose chat/status handling.

Here `viewed=true` records explicit authorization/request to release a receipt, **not proof of remote acknowledgment**. Queue success is not server receipt success. No host packet trace or post-fix two-phone test has been obtained. Group batches, PN/LID conversion, background/notification replies, voice notes, view-once, and status routes need physical acceptance tests. Media played/view-once and status paths retain their existing separate logic; do not claim the text-receipt regression tests validate all those paths.

## Upstream audit and compatible ports

Full per-commit ledger: [docs/UPSTREAM_AUDIT_2026-10-06.md](docs/UPSTREAM_AUDIT_2026-10-06.md).

The previous original-upstream endpoint came from Community commit `26087b95`: `4d91b33cadd8d389e9b206ac9d31364152bd4460`. Audited `Dev4Mod/WaEnhancer` through `749c3cae4a1f1cffc2572a4a2365517ba6ce0322`, accounting for 55 subsequent commits, including the translation PR merge.

The X lineage URL in `SOURCE_URL`, `mubashardev/WaEnhancer`, returned HTTP 451 with a DMCA block. The user supplied the accessible `Dev4Mod/WaEnhancer` URL in response. A distinct accessible newer X snapshot remains unavailable, so newer X commits are **not audited**. A future agent needs an accessible repository or user-provided source snapshot to complete that part.

Compatible ports in this branch:

- `7cd51141`: exact outgoing message-job suffix.
- `02c63bd2`: slider values normalize before persistence and clamp after step rounding.
- `d42cec04`: shared download-document/tree path resolver accepts `raw:`, `msf:`, numeric IDs.
- `a27a81a8` / `068fa4df`, adapted subset: safe typed locked-contact list field, copies immutable lists and writes replacement. Keep Community's removal of locked contacts; upstream's filter keeps those contacts, the opposite behavior.
- `301a378b`, `1706457c`, `4ffb4204`: independent `MinorFixes`, narrowly initialized before DocumentPickerActivity via Instrumentation. Reuses initialized ML Kit context where available and supplies ProviderInfo if the manifest lookup fails. Failed lookup/initialization remains local to this compatibility fix.
- `bb8d5fb2` / `cdaded2a`: stream media download/decrypt via disk, validate key/length, use MIME-family fallback, separate every preview's dialog/files, and perform UI cleanup on its own UI path. Community retains its WebView preview. `MediaPreviewPayload` handles the encrypted payload excluding the existing 10-byte trailer convention; this port does not introduce MAC verification.
- `b6bbc238`: move online-status/presence work off row binding, bounded one-second cache, suppress repeated pending JID requests and check row JID before applying async results. Add newer last-seen anchor/producer-caller resolver while retaining the old anchor.
- `7e8f3714`: remove broad container layout animation from base/home fragments.
- `ced5040c`, `a4771a0e`, `64aaff6a`, `5290929c`: WA/Business catalog entries 2.26.35.xx–2.26.38.xx. These are candidate compatibility declarations, not device tests.

Already covered or intentionally separate: recording selection share/delete, module-package themed context, schema-aware backup/import/reset, Community versioned release naming/updater, and direct NoScrollListView height assignment. Original-upstream release numbers and reset-on-install behavior are not Community policy. Detailed reasons and remaining partial ports are in the ledger.

## Validation and next acceptance

- 14 focused JUnit tests passed: 5 receipt-policy scenarios, 5 immediate-worker/rollback cases, 4 streamed payload/corruption cases.
- Java 17 compilation check passed for 11 changed/new classes (`HideSeen`, `SeenTick`, `LockedChatsEnhancer`, `ShowOnline`, `MinorFixes`, `MediaPreview`, `MediaPreviewPayload`, `ReceiptPolicy`, `ReceiptRelease`, `RealPathUtil`, `FloatSeekBarPreference`), plus the extracted actual changed DexKit resolver. Android API 36 and DexKit APIs are real cached artifacts; unavailable internal/AndroidX/Material/Kotlin/OkHttp dependencies are represented by signature stubs. This is a partial type/API check, not an APK build or runtime test.
- Full `:app:testWhatsappDebugUnitTest` was attempted and blocked at downloading Gradle 8.14.5 (`Network is unreachable`). Project requires a JDK 21 toolchain while targeting JVM 17; available local runtime here is Java 17.
- `git diff --check` passed. Edited resource XML and the Git-mode-120000 CLAUDE.md symlink were verified.
- No workflow dispatched; no APK built or installed; no new device result.

Next physical check on the affected build: hide delivered + blue on reply enabled, verify one tick while receiving/reading without reply, send a normal chat reply, then verify two blue ticks on the sender without using the manual menu. Repeat with multiple pending messages and a notification reply. Compare with Hide Delivered off / Hide Read on: two gray ticks before reply, blue after reply. Confirm manual release, custom per-contact overrides, ghost mode, groups, self chat, PN/LID contacts, voice/view-once and status routes. Also check document picker, recordings list, online row recycling, concurrent media previews and cancellation.

## Unified review checkpoint — 2026-10-06

Review base: `a951ab07`; current branch: `fix/unified-runtime-review`. Detailed findings, scope and acceptance: [docs/UNIFIED_RUNTIME_REVIEW_2026-10-06.md](docs/UNIFIED_RUNTIME_REVIEW_2026-10-06.md).

Implemented:

- Correct #65's unresolved status-reply routing comment through resumed viewer + matching author PN/LID; keep outgoing contact routing for other jobs. Decouple status tracking from the manual-button toggle and correct `UserJid.getUserRawString()`'s null guard.
- Confirm #64's opacity comment was already fixed in `8bdfbe9e`; retain the 72% no-optics fallback. Fix whole-view capture exclusion so native text/children survive while material backgrounds suppress themselves; retain separate glass-host exclusion.
- Snapshot/concurrent activity and contact callback registration, per-feature dispatch error isolation, locked activity-map iteration, snapshot status menus and registration API for both download/share consumers. Isolate status menu build/click failures.
- Serialize receipt DB/cache reads and writes; prevent delayed hiding callbacks from revoking explicit authorization; report success only after one updated row.
- Preserve known version 4–6 edit history and original receipt rows on history upgrades. Archive the source receipt table, merge duplicates with authorization retained, recreate indexes and validate logical-row counts in the existing SQLiteOpenHelper transaction. Older row-ID schemas and downgrades retain their files instead of destructive resets. Below version 4, mapping remains unresolved and existing recovery can use an empty in-memory store.

Validation: 212 current-source JUnit tests passed across 27 classes, five production-SQL SQLite migration/rollback cases passed, partial Java 17/API 36 checks passed, all resource/manifest XML parsed and `git diff --check` passed. These are offline tests/API checks with the specific shims/signature stubs described in the review report. They do not establish a full APK or device result. No workflow dispatched.

Full build attempt: `./gradlew :app:assembleWhatsappDebug :app:testWhatsappDebugUnitTest --no-daemon` failed at Gradle 8.14.5 distribution download (`Network is unreachable`) before project configuration. Only Java 17 is installed. Need an accessible JDK 21/Android SDK 36 environment with Gradle/dependency access to establish the mandatory baseline. Keep framework stages pending until that is resolved.

Refreshed `Dev4Mod/WaEnhancer/master`: still `749c3cae4a1f1cffc2572a4a2365517ba6ce0322`, zero newer commits. The X-lineage source remains unavailable as previously recorded. Original installed evidence remains Community v0.0.8 / WhatsApp 2.26.33.76; no new installed build evidence was supplied.

Published ready-for-review [PR #66](https://github.com/igorcv88/WaEnhancerXCommunity/pull/66), without merging. Its implementation commit is `be90f84475be867b722fc8aaca16850ea6d14bfe`; the remote implementation tree matches the locally validated tree `a8e757797f2ddcb02afdadc71f8262e81c1983fe`. #64's already-fixed opacity thread is resolved; #65's routing thread remains open until its corrective PR is merged.

Next: establish the full baseline; start the first Kotlin UI slice with Java-compatible signatures, then advance through the dependency order below. Preference transport's eager editor IPC, unacknowledged commit and dropped trailing hydration notifications require correction in that stage. Translation, system emoji, public recording storage, contact-picker and remaining compatibility ports remain separate.

## Pending migration program for a new instance

The user requested a new-instance migration prompt. The foundation migrations below remain **unimplemented**. Current stabilization implements prerequisite runtime/data fixes, with a full build baseline still required before framework adoption. Begin by refreshing origin/upstream refs and reading this branch/PR: carry the receipt fix forward whether it has merged or remains open. Audit commits newer than the pinned upstream head rather than assuming the snapshot is still latest. Reuse related migration PRs if present; otherwise open ready-for-review migration PRs linked to this task.

### Suggested sequence and concrete source anchors

1. **Inventory and build baseline.** Compare the actual current Community tree to the pinned original-upstream snapshot. Map features, Java/Kotlin APIs, injected/standalone contexts, preferences, DB stores, resources, lazy loading and diagnostics. Establish a full JDK 21 APK/test build and document an independently installed version baseline. Update `ARCHITECTURE.md` when an authorized migration changes implementation, while retaining identity, data and trust boundaries.
2. **Kotlin UI and shared infrastructure**, `205ed7b6`, `e94bbe1c`, with `21e46c8c` and `0814d8a4` resource/context fixes. Convert/adapt classes incrementally; retain Community's settings editors, embedded settings, FeatureCatalog, UI search, updater, backup flows and theme controls. Keep Java/Kotlin interop signatures compatible during staged conversion. The upstream UI conversion includes 107 files and is not a drop-in patch for this fork.
3. **YukiHookAPI/KavaRef/KSP**, `9a768f9b`; subsequent loader corrections `0aed9011` and `df47d4f1`. Upstream snapshot adds YukiHookAPI 1.3.2 and KavaRef core/extension 1.0.3; verify current compatible dependencies before adoption. Source build adds the Yuki Xposed KSP generator, reserved package ID `0x64`, and explicit asset/resource merge dependencies on `ksp<Variant>Kotlin`. Establish exactly one functional generated entrypoint, reliable resource loading and per-feature install/error/trigger diagnostics. Preserve lazy feature ordering, module/host class loaders, current receipts fix and Community's app identity. Verify generated assets in the APK; source compilation alone does not prove LSPosed loads it.
4. **Preference bridge**, `92211b6e`, coordinated with step 3. Upstream migrates to crossbowffs RemotePreferences and removes its world-readable workaround. Community already has `HookProvider`, `ProviderPrefs`/schema allowlisting and `private_config`. Compare contracts; retain caller checks and public/private separation in the chosen transport. Verify UI changes reach hooks, hook writes return to the module safely, restart broadcasts remain coherent, and backup/restore includes only intended domains.
5. **Resolver cache/DataStore**, `2d646d00`. Adapt memory-first typed/validated codecs, namespaced keys, serialized/batched writes and cache invalidation across host APK/version/account changes. Evaluate upstream's synchronous initial load (`runBlocking`) in the host startup path. Preserve valid legacy cache data where useful, refresh invalid entries, and scope corruption recovery to regenerable cache. Include WppCore/ReflectionUtils/MediaQuality changes bundled with the cache commit only after comparing their semantics.
6. **Status/provider plumbing**, `be375d38`, and upstream Room-based history/FStatus dependencies where required. Community uses `StatusItemWaex`, adapted `MenuStatusListener`, and SQLite `MessageHistory`. Migrate all providers/consumers together; preserve the existing hidden-receipt rows and explicit authorization semantics. If adopting Room/history entities, write a data-preserving migration from Community's actual schema and validate old/new account stores and rollback. Room adoption is a design/compatibility task, not an already-applied port.
7. **Feature ports after the foundation.** Implement configurable translation/context menu, default emoji, public recording storage, and remaining compatibility subsets below in focused PRs. Update the handoff and source ledger as each item is completed; a migration of file syntax/framework does not automatically complete a feature port.

### Pending feature and compatibility work

- **Translation**: `342c557f`, `9ce0f6be`, `0814d8a4`. Global/per-chat source/target language settings, opt-in automatic translation, context-menu manual translation, inherited settings and reset, PN/LID normalization, full group IDs, view recycling, weak UI references, bounded cache/pending/failure backoff. New Google requests should follow the user's explicit opt-in choice. Community currently hooks UnityMessageTranslation and defaults target to locale; upstream replaces that dependency with its own UI/provider. Carry the shared CopySelectionMessage action behavior forward when adapting ContextMenuActionProvider.
- **Default/system emoji**: `ee54e8f9` and the upstream feature's dependencies. Subclass traversal for draw/getSize spans and emoji-text validation need the full feature, settings/catalog exposure and rendering checks. Community has no DefaultEmoji feature to patch directly.
- **Public storage and call recordings**: `e548ff31`. Adapt MediaStore pending URI lifecycle, failure cleanup and saved-location notifications; align RecordingsFragment discovery, sharing/deleting and backup with URI outputs and fallback paths. Community already supports selection share/delete (`eb0a2ed7` equivalent), so retain that implementation.
- **Contact picker**: remaining `21e46c8c` nullable build/attach/resource fallbacks. ModuleContextWrapper is already module-package themed; incorporate actual missing behavior without duplicating a second module resource context.
- **Audio/resolver/reflection subset**: remaining `a27a81a8`; compare narrower audio-origin strings/primitive matching and audio-type behavior against Community's existing features before porting. Keep argument helpers' supported semantics, including index handling, and record version-specific evidence.
- **Earlier deferred upstream features** recorded in Community commit `26087b95`: account-specific WhatsApp preference reading (`CDSharedPreferences`, `b376762f`) and CaptureDevice (`ce90c7dd`). Community already ports account data directory resolution, intent-based current conversation JID and typed ShowOnline args. Reassess the deferred reader/device feature when its dependencies are present; do not mark them as implemented by this audit.
- **Separate X source availability**: obtain an accessible newer source/commit history for the X lineage if the user wants that audit completed. The DMCA-blocked URL supplies no new source evidence.
- **Liquid Glass #64** is now merged into the unified baseline. Preserve its opt-in adapters and capture/resource contracts during framework conversion; its physical calibration is still pending.

## Conversation log

- 2026-10-06, Codex: fetched current Community and original-upstream histories; recorded the X API block. User supplied installed versions and corrected Hide Delivered to enabled. User selected compatible fixes now, new features/migrations separately, then explicitly requested migration state and a startup prompt for a new instance. Implemented the fixes and validation above. Published PR #65, ready for review. The remote implementation tree was verified to match the locally validated tree (`dac5266ed21bfeec25a36c1f769f35005cb49dc6`).

- 2026-10-06, Codex: user merged #64/#65 and requested immediate unified-runtime review before migration. Implemented the findings above, refreshed upstream and published ready-for-review PR #66 without merging. The framework migration gate remains blocked by the full build environment; no APK or physical validation is claimed.

## PR #66 P1 follow-up — 2026-10-06

Confirmed review thread `PRRT_kwDOT1qrJs6pldOj`: matching only the visible author allowed a delayed reply to status A to release status B from that author. Replaced automatic viewer-based selection with lookup of the outgoing job's `id`, an explicitly outgoing host key and its original quoted status key. Validate the exact incoming status ID and author PN/LID; pass only that item in an immutable list to the receipt worker. No visible-item fallback is permitted. Manual single/all-status actions retain their selected-list behavior. An unavailable outgoing identity skips automatic release for that job; the host `id` field/original-key resolver/lookup must be checked on-device, including ordinary chat replies. Avoid the legacy `Key(String, UserJid, boolean)` constructor here because it currently hardcodes incoming keys, and avoid the original-key wrapper's outgoing-message cache.

Validation: 216 offline JUnit tests across 27 classes passed after compiling the changed selector/test source; four additional regressions cover delayed A/B selection, missing/wrong quote, own/chat quote rejection and immutable queued selection. Partial Java 17/API 36 hook compilation and `git diff --check` passed. Initial harness invocation from the repository root failed three source-path checks; rerunning from the required `app` directory passed. Full APK/device checks remain blocked as recorded above. Update the existing ready-for-review PR #66; do not merge or dispatch workflows. Next physical acceptance: reply to A, advance to B before the queued send runs, and verify only A is released; repeat after closing the viewer and with PN/LID contacts and normal/notification chat replies.

## Public Android SDK build fix — 2026-10-06

The user supplied a runner failure at `:app:compileWhatsappDebugJavaWithJavac`: `Choreographer.getFrameTimeNanos()` is absent from the public SDK. `libdexkit.so` stripping is a nonfatal warning. The prior cached Android 36 artifact was a framework jar exposing hidden APIs, so earlier partial API checks did not establish public-SDK compatibility. The full baseline remains incomplete despite the runner reaching Java compilation.

Replaced the hidden call with a shared UI-thread `GlassFrameClock` fed by public `Choreographer.postFrameCallback` timestamps. Coalesce requests across windows, retain once-per-token pre-draw capture/budgeting, and retain the 100 ms software fallback. Bootstrap handles the initial missing token. An idle-to-active first traversal may retain the previous backdrop until the next callback. Callbacks do not self-schedule or invalidate views and retain no provider references.

Validation: three clock regression tests passed; partial renderer/backdrop/adapters/settings compilation passed using the public API 36 SDK stub jar mirrored by `Sable/android-platforms` at `1e98db1a199e8f7f85541af26bfc27019501b132` (confirmed hidden getter absent). Third-party/module signature stubs remain; full APK and frame/device acceptance remain pending. `git diff --check` passed. No workflow dispatched. Published ready-for-review [PR #67](https://github.com/igorcv88/WaEnhancerXCommunity/pull/67), without merging, because #66 has merged. Implementation commit `ccf46969fc6bcfa3580802466426e4a0b907d9fe` has the validated tree `70a0171d234c8baaba409edb9ac902fe1e8d2ba1`. Next: rerun the user's manual full build on the follow-up head, then validate motion, idle resume, multiple windows and bootstrap before marking the build gate complete.

## Preference-bridge audit — 2026-10-06

Branch `ccr-dd7125c8-r29snt`, based on master `88f5799` (PR #67 merged). User report: none of the eight #64 Liquid Glass surfaces worked inside WhatsApp, while the floating bar did.

Root cause (confirmed in code): `HookProvider.get_all_preferences`/`get_preference` serve only keys that `PreferenceSchema` marks `Store.PUBLIC`, and `put_preference` rejects unknown keys. The `LiquidGlassSettings.Surface` keys (`liquid_glass_{toolbars,search,fab,composer,quotes,bubbles,cards,panels}`) were never registered, so `AppLiquidGlass` always read `false`. Same bug class as PR #60. `AppLiquidGlass` installs its hooks unconditionally and gates at runtime, so registration is the whole bridge fix.

Audit of every literal hook-side read (`prefs|pref|mPrefs|activePrefs.getX("key", …)` under `xposed/`), tile-service keys and key constants found the same defect elsewhere:

- `ghostmode_actual`, `dndmode_actual`: Ghost/DND state toggled by QS tiles and the WhatsApp home menu. Absent from schema → hooks (`HideSeen`, `FreezeLastSeen`, `TypingPrivacy`, `DndMode`, `MenuHome`) always saw `false`; the home-menu toggle's provider write was also rejected. Registered PUBLIC_SETTING/PUBLIC.
- `call_recording_use_root`: written after a successful `su` check, read by `CallRecording`. Registered RUNTIME/PUBLIC (device-local, not exported).
- `custom_versions_wpp`, `custom_versions_business`: user-added supported versions read by `FeatureLoader`. Registered STRING_SET PUBLIC_SETTING/PUBLIC.
- `ContactOnlineNotificationsTileService` toggled nonexistent key `show_toast_on_contact_online`; the real setting is `showonline`. Fixed tile and MainActivity long-press scroll target.
- `BaseTileService` always wrote the default (public) file; `SmartTypingTileService`'s `always_typing_global` is schema-PRIVATE, so tile and settings screen diverged. Tiles now use `PreferenceStores.storeFor`.
- `BasePreferenceFragment` used `waex_color_mode`/`waex_color_preset`; real keys are `wae_*`, so preset disabling under Monet and activity recreation never ran. Renamed.

Regression tests: `LiquidGlassSettingsTest.everySurfaceKeyCrossesTheHookBridge`; `PreferenceSchemaTest.everyHookReadKeyCrossesTheBridge` (source scan with a documented allowlist of no-writer defaults), `everyTileKeyIsInTheSchema`, `hookStateKeysArePublic`. Negative check: with the original schema/tile, all four fail naming the broken keys.

Validation: this container now has JDK 21 + Android SDK 36 (installed in-session). `./gradlew :app:testWhatsappDebugUnitTest` passed: 338 tests, 44 classes, 0 failures. `./gradlew :app:assembleWhatsappDebug` succeeded — first full APK build of the post-#67 tree (confirms the public-SDK `Choreographer` fix compiles). Release variant/signing not built. Maven Central rate-limited (429) twice before succeeding. No workflow dispatched, no device test.

Reported, not fixed (need product decisions):

- `always_typing_global`/`_mode`/`_target`/`_contacts` have UI and a tile but no hook consumer; the global Always Typing feature appears unimplemented in hooks (per-contact `AlwaysTyping` lives in CustomPrivacy JSON).
- `HookBL` reads `bootloader_spoofer_default_xml`, which nothing writes; non-custom bootloader spoofing is a no-op. Embedding key material would violate the invariants.
- Module-only keys absent from the schema (`update_alert_frequency`, `downgrades_enabled`, `obsolete_downgrade_notice`, `community_repo_stats_*`) are not bridge failures, but are excluded from settings backup.
- The source scan only sees literal keys; constant/variable keys are covered by targeted tests (Glass, bottom bar, custom versions).

Next device check: install over the current build without clearing data; enable only Toolbars and Composer, force-stop WhatsApp, reopen. If both render glass, the bridge was the global blocker; then evaluate per-surface discovery (Bubbles DexKit resolver, Panels Dialog/PopupWindow hooks, GlassSurfaceCatalog IDs on 2.26.33.76). Also verify Ghost/DND tiles and home-menu toggles now affect behavior, the contact-online tile toggles `showonline`, and root call recording uses root.

## Liquid Glass startup crash — 2026-10-08

PR #68 merged as `7d62edc`. Branch `ccr-dd7125c8-r29snt` was restarted from that master for this follow-up.

User report (device, after updating to a build containing #68): WhatsApp opens on the last conversation, header/quotes/bubbles show the no-optics fallback material for about one second, then the process dies. The user's screenshot confirms the fallback drawing on toolbar, quotes and bubbles before the crash. No logcat/tombstone was supplied yet.

Diagnosis (from code, not yet confirmed by a log): #68 made the surfaces reach the hook for the first time, so #64's app-surface optics path ran on a device for the first time. `SharedGlassBackdrop` recorded the window into a `RenderNode`; `GlassMaterialDrawable` drew that node inside each bound view's background. On a hardware canvas `View.drawBackground` records the background into the view's cached `mBackgroundRenderNode`, so the window recording references a node that draws the material, which draws the recording: a display-list cycle. HWUI recurses on the RenderThread and crashes natively. The drawChild capture hook cannot prevent this (background nodes do not go through drawChild), and Java try/catch cannot contain it. Timing matches: fallback before the first capture, crash when the first optical frame closes the cycle. The floating bar uses `GlassSurface`, a separate pipeline, which is why it never crashed.

Fix: remove the GPU `RenderNode` capture and its optical branch. App-surface glass now samples the existing software bitmap snapshot (≤400k px, ≤0.35 scale, ≥100 ms between captures), which holds pixels, not references, so no cycle can form. A child that throws during capture (for example a hardware bitmap that a software canvas cannot draw) is now skipped and logged instead of aborting the whole capture. Invariant added to `ARCHITECTURE.md`.

Trade-offs: the backdrop updates at most every 100 ms (a throttled frame schedules one trailing capture so an idle UI does not keep a stale snapshot; Codex P2 on #69) and at reduced resolution, so glass can lag behind fast scrolling; software capture runs on the UI thread (cost not measured on a device). The ripple capture guard now never triggers (hardware canvases only) and is retained as harmless.

Validation: `:app:testWhatsappDebugUnitTest` 338 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device test; the cycle hypothesis is unconfirmed until a crash log or a passing device run.

Next: user builds via the manual workflow and installs over the current build. If WhatsApp is still unreachable, turn the surfaces off in the module app (or disable the module for WhatsApp in LSPosed) and collect `adb logcat -b crash` / the LSPosed log. Then repeat the narrow test (Toolbars + Composer only), then the remaining surfaces one at a time. Consider a crash-loop guard that disables app-surface glass after repeated early process deaths.

## Liquid Glass rebuilt on WaThemer's pane model — 2026-10-08

PR #69 merged as `eaccbfd`. Branch `ccr-dd7125c8-r29snt` restarted from that master.

User device report after #69 (screenshots): no crash, but the glass sampled the wrong content (the composer showed a sticker that had been above it before a scroll), refreshed at roughly 5–10 FPS and was pixelated; the header and search showed nothing behind them. Causes: #69's fallback was a full-window software snapshot at 0.35 scale and at most 10 Hz, so the composer showed a stale snapshot; and in WhatsApp's native layout nothing passes behind the header, because the list starts below it and the bar has an opaque fill.

Upstream review (user request): the port's primary source is WaThemer, `ayane-04/wathemer@d39b293` (GPL-3.0), per `docs/LIQUID_GLASS_PORT_AND_HEADSUP_2026-10-01.md`. WaThemer never samples from a view's own background. Each surface gets a `GlassView` pane added beneath it (index 0, no reparenting). `BackdropCapture` records only the pane's region on the GPU, from a named source subtree (the message list host or coordinator) over the wallpaper underlay. It refuses any source that contains the pane, directly or through another pane (`safeToCapture`), and falls back to underlay only. It refracts and then blurs, recapturing every frame while moving and every 250 ms when idle. `GlassConversation.kt` floats the header holder and footer with negative margins, pads the list with `clipToPadding=false` so rows scroll behind the chrome, and clears WhatsApp's bar fills.

User decisions: port WaThemer's model; messages may scroll behind the header and composer.

Implemented in this PR:
- `theme/GlassPane`: a port of `GlassView`/`BackdropCapture`, with `LiquidLens` (the floating bar's proven shader) as the optics. It does a 1/4-resolution GPU region capture of the source over the underlay, applies the lens as a `RenderEffect` on a full-size node, and draws the static material below Android 13 or when the lens fails.
- `theme/GlassPaneGraph`: the pure-logic loop guard, with 6 JVM tests (sibling allowed; ancestor, self and direct/indirect reach-back refused; underlay-only safe).
- `ConversationGlassPanes`: a header band (wallpaper only, as in WaThemer), a header capsule (source: coordinator) and a composer pane (source: the list host). The holder and footer float, the list is padded and kept at its end, and WhatsApp's fills are cleared. Every change is recorded and restored when TOOLBARS/COMPOSER is switched off. The send button keeps the static material path.
- `AppLiquidGlass`: the drawChild software capture and its sampling were removed. It now has ripple and `AbsListView.drawSelector` guards that apply while a pane records, and it skips views that panes own. `GlassMaterialDrawable` is now static material only.
- Removed the dead live-sampling code: `SharedGlassBackdrop`, `WindowStackBackdrop`, `GlassWindowOrder`, `GlassFrameClock`, `GlassRenderPolicy`, and their 14 tests.
- Settings copy and `ARCHITECTURE.md` updated.

Not ported yet (they use the static material, with no live sampling): the home header and search (WaThemer `GlassToolbars.kt`/`GlassSearch.kt`), bubbles, cards, panels, quotes and FAB. The header band and pill have no underlay if `conversation_background` does not exist in this WhatsApp version; the pane then shows the source or static material. The idle heartbeat recaptures each pane every 250 ms while a chat is open (WaThemer behaviour).

Codex review (3× P2, all confirmed and fixed): screens are released on window close because the weak key was kept alive by its own panes; a replaced footer or list is restored with its own recorded values before the new one is recorded; header geometry is resolved before any mutation, so a renamed toolbar or Back button leaves the header native.

Validation: `:app:testWhatsappDebugUnitTest` 330 tests, 0 failures (338 − 14 removed + 6 new); `:app:assembleWhatsappDebug` succeeded; `git diff --check` clean. No device test. WaThemer's view IDs (`search_fragment_and_toolbar_holder`, `coordinator`, `footer`, `input_layout`, `whatsapp_toolbar_home`, `conversation_background`) and FrameLayout parents come from its source for its WhatsApp build, not from 2.26.33.76 evidence. A mismatch is logged (`[LiquidGlass/App]`) and that pane is skipped.

Next device checks: open a chat with Headers and Message input enabled. Scroll and verify the messages pass behind and are refracted live (not a stale copy), that the first and last messages rest clear of the chrome, and that the keyboard, reply preview, voice recording, selection mode and search in chat all work. Then switch both off and confirm the native layout returns without reopening the chat. Collect the `[LiquidGlass/App]` and `WaEnhancerX/GlassPane` logs.
