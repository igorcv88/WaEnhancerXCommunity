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

## Live glass on every surface; neutral fallback — 2026-10-08

PR #70 merged as `2bafc9a`. Branch `ccr-dd7125c8-r29snt` was restarted from that master.

Device feedback on #70 (screenshots): the conversation header and composer look right ("perfect"), with messages passing behind them without lag. The user asked for all surfaces to get the same treatment. Two more reports: (1) the fallback seen in power saving, and briefly on the composer while taking a screenshot, is a flat green slab; they want it transparent or minimal, with no GPU effect. (2) The look is "not exactly iOS": too colourful, less like a pane of glass. They like the colour and could not say what to change. Not changed in this PR; see below.

Root causes:
- The fallback came from `GlassSpec.resolve(..., blurSupported=false)`, which applied a 72% fill floor plus an accent `refractionColor` gradient (WhatsApp green). It is used by the floating bar in power saving (`GlassRenderer.blurSupported` is false) and by `withoutOptics()`.
- The composer flash is most likely a dropped display list during the screenshot overlay. The pane drew the fallback until its next capture.

Implemented:
- `theme/LiveBackdrop`: the GPU recording and lens engine extracted from `GlassPane`, shared by panes and drawables.
- `theme/BehindRecorder`: records what the window draws beneath a view: each ancestor's background, plus the overlapping siblings drawn before the branch (ordered by Z, then index). Ordering makes the relation antisymmetric, so two surfaces never record each other. Siblings containing a live `GlassPane` whose explicit source holds the target are skipped. Pure ordering and overlap logic has 5 JVM tests.
- `GlassMaterialDrawable`: a live mode (`captureBehind`), with the static material when the lens is unavailable. It is used for every non-pane surface: other headers, search, FAB, quotes, bubbles, cards, panels.
- `AppLiquidGlass.Session`: one pre-draw pass per window records all live surfaces on the same draw order. It captures every frame while there is motion (scroll or layout) and every 250 ms at rest. Budget: on-screen surfaces only, top to bottom, until their area reaches one window, at most 32.
- `GlassPane`: uses `LiveBackdrop`, and recaptures immediately when its display list was dropped. It may draw its live glass inside another surface's recording, which ordering makes safe.
- Fallback: no accent glow without blur or optics, and a 58% fill floor (was 72%). This applies to the floating bar's layered path too.
- Settings copy and `ARCHITECTURE.md` updated.

Not in this PR:
- Home header and search have live glass, but nothing scrolls behind them yet. Porting WaThemer's home layout (`GlassToolbars.kt`/`GlassSearch.kt`: list extension, lifts, list card, search overlay) is the next PR.
- iOS-likeness is a tuning question for the user. Candidate levers: lower saturation boost and accent tint, a thinner rim and bevel, less displacement, and more transparency.

Codex review (2× P1, 1× P2, all confirmed and fixed): fallback specs now bypass the live pipeline entirely (`LiveBackdrop.wantsLive`); an intentional release no longer counts as a dropped recording, so idle cadence returns after scrolling; a binding whose capture throws keeps the static material without stopping the others.

Validation: 338 JVM tests, 0 failures. `:app:assembleWhatsappDebug` succeeded. `git diff --check` is clean. No device test. GPU cost with many live bubbles is unmeasured; the area budget bounds it to roughly one window of lens per frame while scrolling.

Next device checks:
- In a chat with bubbles, quotes and the composer enabled, scroll fast and check frame smoothness and that no bubble flickers between live and static.
- Check contact info cards, menus and dialogs, and the FAB on home.
- Turn on power saving and confirm a neutral translucent look on the floating bar and all surfaces.
- Take a screenshot and confirm no green flash.

## Optical report review (user's "Laudo técnico Liquid Glass", 2026-10-08)

PR #71 merged as `224e88c`. Branch `ccr-dd7125c8-r29snt`, restarted from that master; this entry is published as ready-for-review PR #72 (docs only, not merged). The next implementation PR should restart this branch from master once #72 merges, or reuse #72 if it is still open. The user supplied a technical report. It audits `2bafc9a`, which is PR #70, before #71. It keeps the report outside the repo; the user re-attaches it. This entry records the assessment and the agreed starting point for the next session.

Verified against current code (`224e88c`):
- **LG-01 warp fold.** The shader samples `u = s + A(1−s/b)²` with `A = 0.62·b·c` (`MAX_DISPLACEMENT = 0.62`, `MAX_BEVEL_FRACTION = 0.32`). `du/ds` at the rim is 0.008 on flat edges (c = 0.8) and −0.24 at the ends (c = 1). The ends fold back over the first ~19% of the bevel. This explains the stretched or duplicated text at the composer top and the header edge in the user's screenshots. Confirmed.
- **LG-02 / LG-07 input is effectively full resolution.** `LiveBackdrop` records with `scale(1/4)`, then `scale(W/w)` into a `RenderNode`. That is a display list, not a raster, so HWUI rasterises text at full resolution and nothing is downsampled. The "quarter-size recording" wording in `LiveBackdrop`/`GlassPane`/`ARCHITECTURE.md` is therefore wrong. The lens's own blur is `t·uBlur` (zero at the rim, a sparse 9-tap), so text behind the composer stays sharp and readable, and gets stretched at the rim. Confirmed.
- **LG-06 scale rounding.** Net scale `W/(4·ceil(W/4)) ≠ 1` when W is not a multiple of 4. Confirmed; now in `LiveBackdrop.capture`.
- **LG-03 no adaptation for panes and drawables.** `GlassSpec.adaptTo()` is used only by `GlassSurface`. Confirmed.
- **LG-05 colour.** `uSat` reaches 1.55 for LIQUID, plus dispersion of 0.55 (R–B up to 2·uSpread) and a specular gain. This matches the user's "too colourful". Confirmed.
- **Tint.** LIQUID fillScale 0.26 × recommended 10% is about 2.75% tint. Confirmed.
- **LG-11 shader recreated on tint change.** `LiquidLens.apply` keys on `fillColor` and resets the active/press uniforms (floating bar). Code confirmed; the symptom is not reproduced.
- **LG-12 budget.** `GlassBudget` counts only `GlassSurface`. #71 added a per-window area budget for drawables; panes are still uncounted.

Outdated by #71 (the report predates it):
- Non-pane surfaces are no longer static; they are live via `BehindRecorder`.
- The fallback is now neutral, with a 58% floor and no accent glow.
- `LiveBackdrop` now holds the capture engine.
- `wantsLive` skips the GPU path in power saving.

Assessment: the report's diagnosis is correct and its priorities are right (geometry, then filtering and contrast, then colour and finish). Its heavy instrumentation (full telemetry schema, 2D Jacobian tooling, linear-colour pipeline) is sound but should follow the visible fixes, not block them.

Agreed next steps (A/B, one variable at a time; each a focused PR):
1. **Geometry.**
   - Cap the effective `A/b ≤ 0.35` after all multipliers, with a starting range of 0.22–0.32.
   - Fix the scale pair: record with `w/W`, `h/H`.
   - Add a JVM test of `du/ds ≥ 0.30` over the profile.
   - Correct the "quarter-size" docs.
2. **Filtering and legibility.**
   - Give the lens a real low-pass input: a separable Gaussian via a chained `RenderEffect.createBlurEffect`, or a real raster downsample, mixed with the sharp input by depth (β).
   - Make tint and contrast protection independent of the aesthetic slider.
   - Adapt panes and drawables to backdrop luminance (`adaptTo` or local stats, τ 80–160 ms).
3. **Colour.**
   - Saturation at 1.0–1.15.
   - Dispersion off, then R–B at 0.5–1.5 px.
   - `layout(color)` uniforms.
   - Offer "Clear (iOS)" as a separate variant if the user still wants it.
4. **Temporal and budget.**
   - Damage-driven capture instead of the 250 ms heartbeat.
   - Count panes in a budget.
   - Preserve interaction uniforms across tint changes.

Device evidence the user can provide on request:
- Native PNG screenshots (the report notes the earlier ones were JPEG bytes with a `.png` name).
- Screen recordings of the scenarios in report §13.3.
- Optionally `adb shell dumpsys gfxinfo com.whatsapp framestats` or a Perfetto trace while scrolling.
- The installed module build/SHA and WhatsApp version.

## Liquid Glass optical corrections, all groups (LG-01..LG-13) — 2026-10-08

Branch `claude/new-session-l41wpa`, restarted from master `7b86573` (PR #72 merged); published as ready-for-review PR #73 (not merged). One integrated PR, as the user asked; this supersedes the plan above for one PR per step. Focused commits, in order: lens model and Jacobian tests; corrected shader; stored switches; capture, timing and budget; settings UI; calibration image; docs.

**What ships.** There is a second renderer behind experimental switches. It is off by default, and off is the original renderer, unchanged: same shader, quarter-scale capture, transforms and 250 ms heartbeat. To use it: Liquid Glass page → *Optical corrections (experimental)*. The master switch turns it on, and every group defaults to on. The *All improvements* and *Original* presets set the master switch. `AppLiquidGlass` publishes the switches on every WhatsApp resume, so no restart is needed. Dependencies are enforced in `GlassOptics.resolve`: adaptive needs filtering, and Clear needs adaptive.

| Group | Report items | Implementation |
|---|---|---|
| 1 Geometry | LG-01, 06, 08 | `LensModel` + `SHADER_V2`. The warp comes from a geometry shape with corner radius ≥ bevel, so the offset is zero before the medial axis. Amplitude is `lens·0.28·bevel`, capped at 0.35·bevel after the curvature bias (now smooth `n.x²`) and the selected-tab band (now a smooth band of width ≥ max(1.6·bevel, 6px)). Red and blue are the same profile at `A∓Δ`, capped the same way. Recording is 1:1. Placement is exact: `inverse(G_target)·G_view` via `transformMatrixToGlobal`, plus the view's own scroll. |
| 2 Filtering | LG-02, 07 | A two-pass `RenderEffect` graph: lens over the platform Gaussian (σ = blurRadius·0.8dp; LIQUID ≈ 4dp), composited over the lens on the sharp input with β = 0.15 at the rim → 1 at 0.65 bevel. The recording is padded by 3σ+2. The 9-tap blur is used only with filtering off. |
| 3 Adaptive | LG-03 | In-shader contrast protection from the filtered backdrop. It uses a closed-form share toward a protection colour opposite the content colour (target 4.5:1, max 0.70; Clear: 3:1, max 0.85) and is active only in the body (t 0.3→0.8). The tint is pulled 0.55 toward the local backdrop. The bar's sampled colour is smoothed, τ = 120 ms (`ColorSmoother`). No CPU readback was added. |
| 4 Colour | LG-05, 09, 13 | Linear sRGB for saturation/tint/protection (`toLinearSrgb`/`fromLinearSrgb`), and `layout(color)` uniforms. Saturation is 1.10 (was 1.55). R–B separation is ≤ 1.5px total and the hairline separation ≤ 0.75px. Lit gain is 0.18 (was 0.26), away 0.07 (was 0.10), hairline 0.75 (was 0.86), and the flat ×1.06 gain is removed. Lighting stays in encoded values (an artistic term, named as such). |
| 5 Temporal | LG-10, 11, 12 | `CaptureScheduler`: captures the frames the window draws anyway, never invalidates on a timer, captures at rest at most every 33ms with one trailing frame, and recovers a dropped recording at once. Value-keyed effects: `GlassSpec.equals`, and an identical re-resolved material keeps its effect. Selected-tab/press uniforms survive material changes. Live panes count in the per-window budget the drawables spend. |
| Clear profile | user request | `GlassSpec.clearProfile()`: tint ≤ 3%, blur ≤ 3, no dispersion, quieter rim, on app surfaces only (the bar keeps its own style). It uses the same renderer. |

LG-04 (provider contract): both providers (BlurView bar, recorded panes/drawables) now get the same corrected lens and the same filtering graph. They still differ in what reaches the lens: the bar's input is BlurView's downscaled bitmap. A shared provider was not attempted, because merging sources risks recursive capture.

**Diagnostics (developer block).** A view selector: raw input, displacement field, Jacobian heat map (green σ_min ≥ 0.30, yellow lower, red fold), synthetic grid, protection/β. There is also a refraction amount slider, clamped to 0.10–0.35. The `[LiquidGlass/App] optics <key>` log line identifies the active switches. `WaEnhancerX/LiveGlass` logs each surface's corrected parameters (size, padding, σ). `tools/liquid-glass-calibration.png` (generated by `tools/make_calibration.py`) is the calibration backdrop to send to oneself.

**Validation (this container).**
- `:app:testWhatsappDebugUnitTest`: 377 tests in 49 classes, 0 failures (338 before + 39 new).
- `LensJacobianTest`: 5184 surface configurations, 2.5×10⁸ channel samples. Min σ = 0.3006, min det = 0.196 (worst: LIQUID 640×132, r = 0, at the cap). The legacy map is confirmed to fold.
- `:app:assembleWhatsappDebug` succeeded.
- `tools/agsl_check.py`: both shaders pass glslangValidator after an AGSL→GLSL translation.

**Not validated.** None of this has been run on a device.
- AGSL acceptance of `SHADER_V2` on the device compiler is unconfirmed. Half/float rules are not checked by the GLSL translation. If the device rejects the program, the log shows `WaEnhancerX/LensV2 corrected lens rejected` and surfaces fall back to the legacy lens.
- GPU cost of the two-pass graph and of Gaussian blur per surface is unmeasured. With filtering on, each live surface runs two lens passes plus a blur.
- Whether `RenderEffect` snapshots runtime-shader uniforms (the reason `LensEffect` rebuilds the effect wrapper after each uniform write) is inferred from Skia's copy-on-write uniforms, not observed. The legacy `updateActive` path relies on the opposite.
- HWUI rasterisation resolution of the 1:1 recording, and the visual result of every group.

**Unfinished / limitations.**
- Protection estimates luminance from the filtered mean. A small bright feature under a glyph is attenuated, not measured by percentile.
- The sharp pass near the rim applies no protection, by design: its weight is 1−β, and the body is β = 1.
- The bar's adaptive colour still comes from `BackdropSampler`, a 24×8 CPU read of the band above the bar, as before. Only its smoothing is new.
- There is no telemetry schema (report §13.1) beyond the log lines above.
- Home chat list under the header (WaThemer `GlassToolbars.kt`/`GlassSearch.kt`) is not started; it is still the next separate PR.

**Device A/B order.** Use the same chat, wallpaper and the calibration image each time.
1. Original vs master on with only Geometry; Jacobian view, then grid view.
2. Add Filtering.
3. Add Adaptive.
4. Add Colour.
5. Add Temporal, with `gfxinfo framestats` before/after.
6. Clear profile.

Collect `[LiquidGlass/App]`, `WaEnhancerX/LiveGlass` and `WaEnhancerX/LensV2` logs, real PNG screenshots, and the module SHA and WhatsApp version.

### Review round on PR #73 (user's independent review, 2026-10-08)

The user reviewed `65e6a09` and found it not mergeable yet. Each finding was checked against the code; all were confirmed except #12. Fixes are on the same branch:

| # | Finding | Fix | Regression test |
|---|---|---|---|
| 1 | Two passes composited `SRC_OVER`, each with coverage: alpha 0.7125 instead of 0.5 at γ = 0.5, β = 0.15 | Each pass carries its own share including coverage (soft γβ, sharp γ(1−β)) and the graph adds them (`BlendMode.PLUS`). The fallback returns are weighted too. | `compositionAppliesCoverageOnce`, `effectGraphAddsThePasses` |
| 2 | Geometry off combined the folding warp with the other corrections | Stable geometry is now an invariant of the corrected renderer (no switch, pref key removed). The legacy warp is only the `LEGACY_WARP` diagnostic view, labelled as folding. | `everyCombinationRespectsItsDependencies`, `legacyWarpIsOnlyADiagnosticView` |
| 3, 9 | Protection only in the soft pass, before lighting, partly in encoded RGB | Protection runs last, after lighting, in linear light whatever the colour group, in both passes. Composite luminance is the linear (1−β, β) mix, so it holds whenever both passes do. Cap raised to 0.85 (white over white needs 0.82). | `perPassProtectionHoldsAfterComposition`, `protectionCoverageByForeground` |
| 4 | `paintExact` culled siblings against the unpadded rect; the addendum adds that siblings were drawn by index, not Z | Culling uses the rect padded by the blur margin (`Painter` now receives the padding). Eligible siblings are sorted by (Z, index). Custom `getChildDrawingOrder` is not visible and falls back to that order. | `marginOnlySiblingsAreRecorded`, `siblingsAreOrderedByZThenIndex` |
| 5 | Budget counted visible area; panes were never refused | `LiveBudget`: one ledger per window in pixel-passes, `(w+2p)(h+2p)×passes` (3 with filtering), capacity 3 × window area. Panes are admitted in their pre-draw and refused past capacity; drawables get the rest. | `costCountsMarginAndPasses`, `panesAreAdmittedAgainstCapacity` |
| 6 | The bar might keep its old effect after a toggle | `GlassOptics.revision()`. `GlassSurface`'s pre-draw compares it and posts `refresh()`. Panes and drawables key on the optics anyway. | `publishReportsChangesOnce` (revision) |
| 7 | Effect key used a partial hash, which ignored `innerShadow` and others | Rebuild decided by full `GlassSpec.equals` (`LensEffect.sameMaterial`). Identity with the temporal group off. | `everyMaterialFieldTakesPartInEquality` (each field perturbed by reflection) |
| 8 | Docs claimed no timed invalidation; per-frame allocations | Docs corrected: one trailing invalidation at rest, no heartbeat. `Session.onPreDraw` reuses its lists, `Rect` and primitive sort keys. Morph still rebuilds the `RenderEffect` wrapper per animation frame (unmeasured). | — |
| 10 | Optical vs visual corner radius | Not changed. The sweep includes 24×24 and 56×56 surfaces at radius 0 and 4. Whether the rounder optical corner is visible is a device check. | sweep |
| 11 | Jacobian margin 0.0006 | Cap lowered from 0.35 to 0.33 of the bevel. Measured min σ = 0.3347 (was 0.3006). Default 0.28 × selected-tab gain 1.18 reaches it exactly. | `LensJacobianTest` |
| 12 | Codex scroll comment | Kept as answered on the thread. Pixel comparison with a scrolled `ScrollView` is a device test. | — |
| 13 | Global `broken` flag | Compilation failure is permanent; any other failure is retried and becomes permanent after 3. Native RenderThread failures are not catchable and are documented as such. Legacy-equivalence wording corrected (same logic, shared code paths). | — |

Contrast reach, by foreground, within the 0.85 cap (JVM arithmetic, not pixels):
- White content (dark theme) and black content (light theme) reach 4.5:1 over any backdrop.
- The dark-theme hint `#8696A0` falls to about 1.5:1 where the glass is at the white-content limit. WhatsApp's own opaque bar gives it about 4.6:1. Protecting the hint would need near-opaque glass, so it is recorded, not guaranteed.

**Validation.** 387 JVM tests, 0 failures. `tools/agsl_check.py` passes both shaders. `assembleWhatsappDebug` succeeded. Still no device run: first check that the V2 shader compiles on the device and that every surface captures the correct texture without displacement, then calibrate.

## Device feedback after PR #73: flicker, double search rim — 2026-10-08

PR #73 merged as `b471a25`. Branch `claude/new-session-l41wpa` restarted from that master.

**User report (S25 Ultra, all improvements and Clear profile on).**
- The glass switches between live and fallback on every page change, tap and scroll. It never settles.
- The look is "meia boca" (mediocre).
- The home header and search are static pills with nothing passing behind them.
- The Element Inspector records nothing.
- The first report (no change at all) was taken with the corrected renderer still off.

**Cause of the flicker (code reading; not confirmed by a log).** #73's `LiveBudget` charged `(w+2p)(h+2p)×3` per surface against 3 × window area, far less room than the old visible-area budget: an image bubble costs ~1.7M of ~7.6M at FHD+. The session also re-admitted every surface top to bottom on each capture, so any layout change moved the cut-off. Surfaces near it alternated between the live glass and the 58% fallback.

**Fix.**
- Admission is sticky (`admitDrawable`): a live surface keeps its slot while visible, and only new surfaces are refused. Past 1.25 × capacity (a growing pane), only the newest admission is evicted.
- Slots are released when a surface leaves the screen or its binding is restored (weak keys as a backstop).
- Capacity raised to 4 × window area.
- Tests: `LiveBudgetStickyTest`.

**Double rim on the home search field.** `my_search_bar` contains `search_bar`, and both map to SEARCH, so two glass pills were nested. `Session.bind` now keeps one pane per surface, and the innermost view wins.

**Home list under the header/search: not implemented, and design input needed.** WaThemer (`d39b293`, `GlassCards.syncListCard`/`syncListExtension`) does not scroll home rows behind its header either. It puts the list in a glass card below the header and clips the rows to that card. Making rows pass behind the home chrome is new work that needs the home view hierarchy. The Inspector arms only when WhatsApp starts after it is enabled (`InspectorFeature.doHook`), so the likely fix for "nothing happens" is: enable it, then restart WhatsApp (the module's own "Restart WhatsApp" menu entry), then tap.

**Assessment of the screenshots (device, no measurement).**
- Clear profile on: the conversation header carries almost no tint and a light blur, so message text stays readable through it. That is what the profile does, and it looks more like no glass than iOS. Compare with Clear off.
- Home header and search: with nothing behind them, any material reads as a flat dark capsule.
- The floating bar and popup menus look as intended.

**Validation.**
- 390 JVM tests, 0 failures.
- `assembleWhatsappDebug` OK.
- `agsl_check` OK.
- No device run.


## Consolidated report correction — 2026-10-08

Base: `master@cd198492fe23feae3f4fa670ea915d6a1a6e7662`, incorporating #73/#74. Working branch: `fix/glass-session-recovery`. The user supplied the consolidated technical report including its final navigation-trace addendum and instructed execution. No new log collection was requested. No related open glass PR was found in the latest PR inventory.

Implemented:
- `ENDED` remains stop-compatible for existing features; a new `DESTROYED` callback disposes the app-glass session and removes the Activity registry entry. Stop suspends capture/discovery callbacks and observers, retains bindings/layout, and discards captured pixels. Resume revalidates/recaptures in the same session; destroy/detach restores native ownership and cleans up. Deferred discovery collected during suspension is reconciled on return.
- Window-local pane material resolution replaces global foreground/last-material borrowing. Source, underlay, size, radius, material, readiness and visibility changes invalidate recordings. No old-frame image cache is used across chats or resumed visibility epochs.
- Three bounded capture attempts per epoch replace permanent `liveFailed`. Retries occur after 16/64 ms; exhausted transient failures await relevant events. Capability/linkage failures stay disabled. Incomplete recording/effect installation cannot publish live content.
- Header band becomes plain wallpaper continuity with no optics/budget; capsule prefers the resolved list subtree and retains acyclicity guards. Underlays are guarded as well. Composer terminates before the native action disc with a 2 dp minimum gap, in LTR/RTL, without changing touch targets or native foreground controls.
- Battery Saver no longer disables intra-window optics. Temporary capture fallback is explicitly achromatic; V2/legacy shader programs and optics defaults are unchanged.
- Transition/presentation/budget logs (`WaEnhancerX/GlassState`) include timestamps, PID, Activity/root/surface/source identities, reason, optics/material keys, epoch/capture generations, retry count, node/content/capability status, budget status, power-save status and effect rebuild count. No frame-by-frame successful-capture logging. Source dirty flags trigger capture on an already requested frame; identical underlay assignments no longer invalidate panes.

Validation:
- 107 focused JVM tests passed in 12 classes, including 13 new recovery/geometry/neutral-fallback cases. The local harness uses compiled JUnit source and minimal Android class placeholders only for loading graphics-dependent helper classes; these are CPU policy/arithmetic/source checks, not rendered pixels or Android lifecycle instrumentation.
- Partial Java 17/API type check passed for the changed glass pipeline, conversation/session adapters, configuration dependencies, and actual lifecycle callback/registry classes against cached Android API 36 and DexKit. Unavailable app/framework dependencies use signature stubs; the WppCore enum is extracted from the actual source for the lifecycle check. This is not a full app compilation.
- Full Gradle unit-test/APK command attempted; distribution download blocked (`Network is unreachable`, then proxy `Connection refused`). This environment has JDK 17, not the project's required JDK 21. No APK was produced. AGSL programs unchanged; no new GPU acceptance claim.
- `git diff --check` clean. Workflows remain manual-only and none was dispatched.

Acceptance and limits: [docs/GLASS_RECOVERY_ACCEPTANCE.md](docs/GLASS_RECOVERY_ACCEPTANCE.md). A neutral frame is permitted during a genuine unavailable-source/capture failure; persistent fallback after a successful same-root resume is a defect. No retained stale-image workaround, complete source ROI damage tracking, native backdrop experiment, SurfaceFlinger work, or measured energy reduction is claimed. Native geometry, same-process navigation, keyboard/recording transitions, visibility-epoch cleanup, screenshot recovery, and Battery Saver optics still require device verification. Build/test the branch with JDK 21 and a configured SDK before merging. Never merge or dispatch Actions without user instruction.

Publication: the user explicitly approved publication to `igorcv88/WaEnhancerXCommunity` on 2026-10-08. Branch `fix/glass-session-recovery` is published as ready-for-review PR #75: https://github.com/igorcv88/WaEnhancerXCommunity/pull/75. Implementation commit: `4d2fb1320a61df3ca88f14b07dda16fe9e0db732`; its tree exactly matches the locally tested tree `d75bb08e510d2ca385b3e6839db199c3c8838bd6`. Publication used the authenticated GitHub connection because local Git lacks write credentials. No merge or workflow dispatch occurred. The publication approval blocker is resolved; the remaining limits are the full APK build and physical acceptance checks listed above.

## PR #75 independent review corrections — 2026-10-08

Branch/PR: `fix/glass-session-recovery`, https://github.com/igorcv88/WaEnhancerXCommunity/pull/75, correcting reviewed HEAD `da95e7ae03552141de6137110a6d876e7e80c016`. The user explicitly requested all independent-review adjustments plus the GitHub review comments. The two inline Codex findings (discussion IDs `4222748738` and `4222748743`, reviewed implementation `4d2fb13`) match the capture-loop and same-class Activity registry regressions. This entry supersedes the earlier three-attempt exhaustion policy and the previous build-environment blocker.

Changes:
- `RecordingState` distinguishes `UNINITIALIZED`, `RECORDED`, `DROPPED`, `INTENTIONALLY_RELEASED`, `BUDGET_REFUSED` and `TEMPORARILY_UNAVAILABLE`. Only an eligible, visible unexpected drop requests immediate recovery. Missing pixels alone do not force redraw. Pane/drawable budget refusals and intentional releases cannot keep another admitted surface recapturing at rest; hidden panes release their ledger slot and retry callbacks. Static-material policy does not trigger transient retries.
- `SurfaceRecovery` permits autonomous transient recovery: 16/64 ms fast retries, then `BACKOFF` at 1/2/4/5 seconds, capped at 5 seconds. Permanent capability/linkage failure alone disables recovery. Due retries bypass capture throttling without creating immediate damage-driven redraws; legacy scheduling retains its normal heartbeat. A session's earlier callback preserves other surfaces' later retry deadlines. Stop/destroy cancel owned callbacks; no pixels survive a visibility/source epoch.
- `WaCallback` marks the destroyed instance without publishing it into the global class-name association, dispatches `DESTROYED` for that identity, then removes only that instance. A newer same-class resumed Activity and `mCurrentActivity` remain intact. Generic registry `DESTROYED` updates have the same protection.
- Header source is a safe message-list host, never an ancestral coordinator fallback. Conversation panes require `CAPTURE_LIVE_CONTENT`; `CAPTURE_WALLPAPER_ONLY` / `SOURCE_UNAVAILABLE` cannot complete their recovery. The header re-resolves a late safe source on permitted/backoff attempts, so it can recover without another geometry/lifecycle event. Existing source/underlay recursion guards stay active.
- Temporary fallback uses achromatic 18–32% tint, preserving shape, stroke and content colour; permanent renderer loss keeps the existing 58% minimum opacity. These provisional temporary tint bounds still require physical text/icon legibility calibration. V2/legacy shader programs and optical profile defaults are unchanged.
- Resumed window epochs separately log per-surface first presentation, first complete capture, first live presentation and fallback-frame count. Root pre-draw tokens deduplicate draws; nested capture draws are excluded. These timings describe app draw submissions, not confirmed SurfaceFlinger presentation. No successful-capture log stream is emitted every frame.
- Plain `WallpaperUnderlay` architecture is retained. Acceptance instructions explicitly compare the former optical band against wallpaper continuity in static and controlled-scroll scenes; no measured GPU or battery percentage is asserted.

Validation:
- 127 focused JVM regressions passed in 16 classes, including the actual registry/destruction callback with dependency signature substitutes. This includes 20 additional cases relative to the previous 107-test report.
- Full Gradle module test task with JDK 21.0.8 passed: **424 tests, 57 suites, zero failures/errors/skips**, using the real project dependencies and AGP's mockable Android test runtime. New lifecycle tests allocate identity-only Activities through runtime reflection rather than executing Android stub constructors; no device lifecycle/graphics behavior is simulated by those objects.
- The full production sources and all unit-test sources compiled with JDK 21 and JVM 17 target. The build environment now has Gradle 8.14.5, Android SDK 36 and Build-Tools 35/36; public distribution/dependency access uses the environment's proxy and existing system CA trust store. SDK downloads were checked against Google's repository checksums. No source/dependency substitution or project build-setting change was needed for the full Gradle run.
- Full `:app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug --no-daemon` completed successfully. APK: `com.waenhancer.community`, version `1.8.0-alpha1` / code `18001`, arm64-v8a and armeabi-v7a, 15,750,083 bytes. APK v2 signature verification, ZIP CRC and 16 KB ZIP-alignment checks passed. SHA-256: `e94975b7b6482a9ee644bcc06d562c02967ca1c09e935e4155c34f50d782eb61`. This is a debug-signed APK (certificate SHA-256 `3ca8c723404e377a652d3f5f1c9c0c936fb2865f9702fbad6593b7aeff0d56a5`), not a release-key update for an installed release. No install or device run occurred. DexKit debug symbols could not be stripped without an NDK, so Gradle packaged that prebuilt library unchanged; the APK build still succeeded.
- Partial changed-pipeline/API checks also passed; `git diff --check` is clean. Shader source comparison against `da95e7a` is unchanged. No workflow dispatch or merge.

Remaining acceptance: install the appropriate signed test build on the S25 Ultra and run the focused navigation, keyboard/recording, Battery Saver, dropped-recording, late-source and mixed-budget scenarios in [docs/GLASS_RECOVERY_ACCEPTANCE.md](docs/GLASS_RECOVERY_ACCEPTANCE.md). Measure fallback frames and physical GPU/frame time rather than infer them from logical pass counts. Do not expand the architecture without new device evidence.

## Conversation blank with Headers on (post-#75) — 2026-10-08

Branch: `ccr-0fefe1d4-wo0f7v` (ready-for-review PR #76), based on `master@8f11ac5` (PR #75 merged). User device report with screenshots: flicker is gone, but with Liquid Glass *Headers* (TOOLBARS) on, the conversation shows only the header capsule over an empty dark screen (no messages, no composer); the user reports the same "conversations don't load" on home. Headers off: normal and near-correct.

Root cause (code reading, high confidence; no device log): #75 replaced the header band — previously a `GlassPane`, i.e. a childless `FrameLayout` — with `WallpaperUnderlay extends View`, added to `search_fragment_and_toolbar_holder` as MATCH_PARENT. A wrap-content `FrameLayout` measures children under AT_MOST and includes MATCH_PARENT children in its own size. `View.onMeasure` returns the full AT_MOST offer (a childless `FrameLayout` returns 0), so the holder grew to the whole remaining window. With `translationZ = 1` it covered the coordinator, and `syncListPadding` set the list's top padding to that height, pushing every message off-screen. Any screen whose holder `ConversationGlassPanes.sync` picks up (it requires `coordinator`, not the footer) is affected, which matches the home report.

Fix: `WallpaperUnderlay.onMeasure` takes size only under EXACTLY, otherwise 0, like the old band. The holder's second pass measures its MATCH_PARENT children exactly to its final size, so the band still fills the header. Test: `WallpaperUnderlayMeasureTest`.

Validation: `./gradlew :app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug` with JDK 21 and Android SDK 36 installed in-session: 425 tests, 58 suites, 0 failures; debug APK built. `git diff --check` clean. No device run; no workflow dispatch.

Device evidence (user logcat via Termux, 2026-10-08 18:07, build without this fix): `requestLayout() ... MeasuringFrameLayout{... 0,0-1440,3160 #app:id/search_fragment_and_toolbar_holder}` — the header holder measured 3160 px, the full window, while the capsule pane sat at 18,0-1422,174. This confirms the root cause. The conversation capsule pane logged `captureContent=SOURCE_UNAVAILABLE` / `required-source-unavailable` with `sourceId=none` throughout: `listHost()` only accepts children with height > 0, and the squeezed list host had none. The fix should resolve both. No crash, no `WaEnhancerX/GlassPane` warning.

Next: install over the current build, enable Headers, open a chat and the home screen; confirm the list renders, the first message rests below the capsule and the band shows the wallpaper. If home still shows no rows, collect `[LiquidGlass/App]` / `WaEnhancerX/GlassState` logs; the home holder is then a separate path.

## Home header and search diagnostics — 2026-10-08

Branch: `ccr-0fefe1d4-wo0f7v`, restarted from `master@5335d80` (PR #76 merged). User device report after #76: the conversation screen and the floating bar are correct and stable, including in Power Saving. Remaining: (1) the home header capsule shows nothing behind it and looks poor; (2) the SEARCH surface appears to apply only on the Groups tab.

User decision: on home, rows should scroll behind the header (the conversation model), not WaThemer's card-below-header.

Why this PR does not move the home layout yet: #75 shows that a blind layout change can blank a screen. WaThemer (`d39b293`, `GlassHook.kt`/`GlassToolbars.kt`/`GlassCards.extendList`) shows that its home layout has `header` and `pager_holder` as siblings, the chat list is `android:id/list` inside a `ConversationsContainer`, and `my_search_bar` is sometimes seated inside the list as a row and sometimes beside it. That last point is a likely explanation for the per-tab SEARCH difference, but none of it is verified on 2.26.33.76. The `GlassState` log from the device shows one live SEARCH drawable at 1440x144 on home, plus a second drawable (`3d1b788`) stuck in fallback with `budgetStatus=unassigned`; neither line names the view.

Changes (diagnostics only, no layout or rendering change):
- `AppLiquidGlass.bind` logs `BOUND reason=surface-bind role=… name=<resource> size=WxH path=<named ancestors>`, and logs `UNBOUND reason=inner-surface-wins` when the innermost-wins rule drops an outer container.
- `HomeTreeProbe` (`WaEnhancerX/HomeTree`, info level): on the home window (has `pager_holder`, `conversations_coordinator_layout` or `header`) with TOOLBARS on, dumps class, id, bounds, padding, layout params/margins, background, Z and clipToPadding, at most 400 lines per dump and at most 4 dumps per window (a new dump when a tab first adds a list). Read-only.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded; `git diff --check` clean. No device run.

Next: user installs this build and, with Headers + Search on, opens home, switches Chats → Groups → Communities → Calls, then runs `su -c 'logcat -d -v time -s WaEnhancerX/HomeTree:V WaEnhancerX/GlassState:V > /sdcard/Download/home.txt'`. From that: (a) explain the SEARCH per-tab difference from the `BOUND`/`UNBOUND` lines; (b) implement the home float (pull the pager under the header, pad each page's list with clipToPadding=false, keep non-scrolling page content below the header, clear the header fill, record/restore everything, revert if the pager collapses).

## Home rows behind the header; search pill; FAB bind loop — 2026-10-09

Branch: `ccr-0fefe1d4-wo0f7v`, restarted from `master@2aaf484` (PR #77 merged). Evidence: the user's `home.txt` (HomeTree dump + GlassState, 2.26.33.76, S25 Ultra, 1440x2992 content).

Observed layout: `content` (FrameLayout) holds `pager_holder` (0..2992) and `header` (0..168, z=12) as siblings, so the pager already runs under the header. Each chats page is `ConversationsContainer` (vertical LinearLayout): `my_search_bar` (full-width FrameLayout, topMargin 180 = header clearance, no background) holding `search_bar_inner_layout` (the visible pill, GradientDrawable, 36 px side margins), then `WDSList` (`android:list`) at top 348. WaEnhancer's separate Groups page has the same container. Calls already pads its list (paddingTop 168, clipToPadding=false). The header fill is `toolbar_container`'s ColorDrawable; `toolbar` (WDSToolbar) carries our TOOLBARS glass.

Findings and changes:
- **Search "only on Groups"**: both pages' `my_search_bar` were bound and LIVE, but `my_search_bar` is the invisible full-width row, so the glass capsule spanned the screen edges behind the native pill (visible as a rim on one tab, nearly invisible on the other). `search_bar_inner_layout` added to SEARCH; innermost wins, so the pill itself now carries the glass on every chats page.
- **FAB bind loop**: `fab_second` was bound 288 times in seconds. Material's FAB ignores `setBackground`, so each scan saw a "native rebind", restored and rebound, and each bind nulled the button's tint. `bind()` now detects a view that ignores the background, restores its tint, logs `REFUSED reason=background-ignored`, and never retries it in that window.
- **Home rows behind the chrome** (user decision: conversation model): new `HomeGlassChrome`, run on each global layout with TOOLBARS on. Per chats page it pulls the list up to the container top and hands the distance back as top padding (clipToPadding=false), recomputed from `top + shift` each layout (WaThemer `extendList`); raises `my_search_bar` to Z=1 so it draws and takes touches above the list; clears `toolbar_container`'s fill. All recorded and restored when TOOLBARS is off. Guards: header/pager must be siblings with the header over the pager top and at most a third of its height; the container must be a vertical LinearLayout; otherwise the page is left native and logged once.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device run.

Device checks: home Chats and Groups — first chat rests below the search pill as before; scrolling moves rows behind the pill and the header capsule with live refraction; search pill tappable; pull-down, archived row and filters still work; toggling Headers off restores the native layout without restart. Check the FAB keeps WhatsApp's green and the log shows one `REFUSED` line instead of a bind storm. Not handled: Communities/Updates tabs (not in the dump), the header capsule's shape/look itself.

## Home regressions after #78 — 2026-10-09

Branch: `ccr-0fefe1d4-wo0f7v`, restarted from `master@0a08f48` (PR #78 merged). User device report with a screen recording and `home2.txt`: home is sluggish ("todo travado"); chat rows stick to the top as if pulled by a magnet, once settling after a wait and then breaking again after opening and leaving a chat; the header capsule alternates between live glass and the semi-transparent fallback.

Evidence and causes:
- **List oscillation.** The HomeTree dump caught `WDSList ltrb=0,348 margin=-348 pad=348`: the margin was already applied but the list not yet relaid out. `HomeGlassChrome` derived the rest position from `getTop() + shift`; any `scan()` between the mutation and the next layout (discovery runs on `post`) read the stale top and doubled the shift, and the next layout halved it again: a relayout every pass, rows jumping, and `keepAtTop` (scrollBy -1e6) firing each time the list was at the top — the "magnet". Fix: the rest position is computed from the container's padding and the measured heights and margins of the siblings above the list; nothing runs while a layout is pending; the top pin fires only on the first extension; a padding reset by WhatsApp re-bases instead of compounding (logged once).
- **Header fallback flashes.** The home toolbar was re-bound 16 times (each `BOUND` → `DEGRADED` → `LIVE`): WhatsApp replaces WDSToolbar's background on scroll/tab changes, and the scan treated each as a native rebind, building a new drawable that starts without a recording. Fix: when the native background under a bound view is replaced, the existing glass (and its live recording) is reinstalled and the new drawable becomes its mask/restore original (`GlassMaterialDrawable.replaceOriginal`). Logged once as `kept-<SURFACE>`.
- **Diagnostic cost.** `HomeTreeProbe` walked the whole tree on every global layout while the list count stayed below its next dump. Removed; its findings are recorded above.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device run; no frame-time measurement. Remaining: the live glass on the header and the search pill each records the pager subtree every scrolling frame; if scrolling is still heavy after these fixes, measure (`adb shell dumpsys gfxinfo com.whatsapp` / `su -c dumpsys gfxinfo com.whatsapp`) before changing the capture model.

## Root-cause review of the glass flashes; first-capture fix; performance baseline — 2026-10-09

Branch: `ccr-0fefe1d4-wo0f7v`, from `master` after PR #79. The user confirms home is much better, but asks whether the fixes treat causes or symptoms: the search field (and likely menus) still flash to the fallback, and scrolling feels like 60–80 Hz rather than 120 Hz.

Why only some surfaces flashed: there are two pipelines. The conversation header/composer are `GlassPane` children and the floating bar is `GlassSurface`: views the module owns, captured in their own pre-draw at their own size; WhatsApp never touches them. Home header, search, FAB, quotes, cards and panels are `GlassMaterialDrawable` backgrounds installed on WhatsApp's views. That path had two structural defects:
1. Any native background swap discarded the glass and built a new one (fixed in #79 by keeping the glass; not a mask: the rebuild was pure churn).
2. **Every new drawable failed its first capture.** A background gets its bounds only when the view first draws it, after the pre-draw capture pass, so `captureBehind` saw empty bounds and returned false: the first frame always showed the fallback. Device logs: 24 of 27 binds in `home2.txt` and 73 of 307 in `home.txt` logged `REVALIDATING->DEGRADED reason=capture-not-ready` straight after `BOUND`. This is what flashes on every new menu, tab page and search pill. Fixed here: `captureBehind` takes the view's size when the bounds are still empty (the view's own draw sets the same bounds).

Performance baseline (user `gfx.txt`, home scrolling, this build's predecessor): 1548 frames, median frame 19 ms, p90 24 ms, p99 65 ms; **GPU median 9 ms, p90 11 ms**. A 120 Hz frame has 8.3 ms, so the GPU alone exceeds the budget; the user's 60–80 Hz impression matches. Not yet attributed per surface. Likely costs, in order to measure: each live drawable re-records the content behind it and runs the blur/lens `RenderEffect` at full resolution every scrolling frame (LG-02: the "quarter-size" recording is a display list, so nothing is actually downsampled); home has several live surfaces at once (header, search pill, floating bar, FAB, filters). Candidate fixes, each to be measured on the device before/after with `dumpsys gfxinfo`: a real low-resolution capture (render the backdrop into a scaled layer before blurring); one shared backdrop per window instead of one per surface; and moving home header/search to the pane model the conversation screen uses, so both screens share one pipeline.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device run.

## Search-only rows; idle frame rate — 2026-10-09

Device report (user, after #80): with only Search on, nothing scrolls behind the search pill or the header; with only Headers on, rows show around the search row but not behind the header capsule; with both on, both work. The floating bar is independent and unaffected.

- Search only: `HomeGlassChrome` ran only when TOOLBARS was on, so with Search alone the list was never extended. Fixed: the list extension runs when Headers or Search is on; the header fill is cleared only with Headers and restored when Headers is off while Search stays on.
- Headers only, header capsule shows no rows: cause not established. In code, the header capture (`BehindRecorder.paintExact`) draws `pager_holder` as a sibling drawn before `header` regardless of the Search switch. Needs a `GlassState` log and a screenshot with Headers only.
- Idle measurement (`idle_header.txt`, Headers only, untouched home, includes switching back to WhatsApp during the run): 154 frames in 10.1 s (~15 fps), frame p50 8 ms, GPU p50 3 ms. A glass-off idle baseline (`idle_off.txt`) was not supplied, so the share caused by glass is not attributed yet.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device run.

## Headers-only evidence; idle baseline — 2026-10-09 (PR #81, second commit)

Device evidence (user, build before #81): screenshot with Headers only; `header_only.txt` arrived empty (the logcat capture produced nothing); `idle_off.txt`.

- Screenshot, read from pixels: the native search pill is translucent, about 9.5 % white over the app background ((34,38,43) over (10,16,20)). Rows showing through it means the list extension and the Z raise work with Headers only. The header capsule interior is (10,17,21), the app background, and the scrolled row is cut flat at the header's bottom edge. So with Headers only, something opaque in the app-background colour sits between the rows and the toolbar glass. The prime suspect is the `toolbar_container` fill (or our transparent replacement recoloured in place) set by WhatsApp's lift state on scroll without a layout. `HomeGlassChrome.sync` runs only on global layout, so it never sees that change. Not proven.
- Change: `HomeGlassChrome.frame(root)` runs from the window pre-draw before captures. With Headers on, it re-clears a natively replaced fill and resets a recoloured replacement. It logs `state=HOME_CHROME reason=header-chain` whenever the header chain changes, covering the header/fill backgrounds, header Z/alpha, the action taken, and both switches. The pre-draw cost is two cached weak references and three getters; logging happens only on change.
- Idle baseline, glass off (`idle_off.txt`): 526 frames in about 10.1 s (~52 fps), frame p50 11 ms, p90 14 ms, GPU p50 3 ms, 70 % deadline missed. WhatsApp redraws on its own at rest (a "typing…" row was on screen). Idle frames are therefore not caused by the glass, so stopping recaptures at rest is dropped as an optimization. The optimization order is now: (B) one shared backdrop recording per window for the home header and search; (C) real low-resolution blur. Measure each with `dumpsys gfxinfo` before and after.

Validation: `:app:testWhatsappDebugUnitTest` 425 tests, 0 failures; `:app:assembleWhatsappDebug` succeeded. No device run.

Next: the user installs this head with Headers only, scrolls the home list, and sends `logcat -d -v time -s WaEnhancerX/GlassState:V` (captured without `logcat -c` immediately before it) plus a screenshot. If rows now show behind the header and `action=fill-*` lines appear, the cause is confirmed. If not, the `HOME_CHROME` signature shows which header-chain background is opaque.
