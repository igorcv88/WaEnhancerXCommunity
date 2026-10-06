# Agent handoff

This is shared working state for Codex, Claude, GPT, Antigravity, and subsequent agents. Read `AGENTS.md` and `ARCHITECTURE.md` first. Update this document after each task; preserve decisions and evidence instead of treating earlier plans as completed work.

## Current task — 2026-10-06

Repository: https://github.com/igorcv88/WaEnhancerXCommunity

Base: `master` at `4f4d0c64fff855508289e08ee064b5ff3261b2c4`. The installed Community `v0.0.8` has the same tree as that base. Working branch: `fix/receipts-upstream-audit`. Published ready-for-review PR: https://github.com/igorcv88/WaEnhancerXCommunity/pull/65 (`draft=false`, open, not merged). Initial implementation/audit commit: `47ba889050551df0f1128be0b2d70cb9b0e5410c`. A documentation follow-up records publication; use the actual PR head for subsequent work.

The user confirmed Community `v0.0.8`, ordinary WhatsApp `2.26.33.76`. Initial report said Hide Delivered was off; **the user corrected this: Hide Delivered was enabled**. Correct target behavior: receipts remain hidden before replying, but Send Blue Ticks upon Reply releases delivered/read state when the user sends a reply. Manual Send Blue Tick / Mark Viewed currently releases both, according to the user's two-phone comparison. Treat that as reported device evidence, not a test of this branch.

User decisions:

- Compatible upstream fixes and receipt correction now; new translation features and architectural migrations separately.
- Include all migrations/pending changes here and provide a prompt to start migration in a new instance.
- English reasoning/documentation/replies; ask for missing facts or user-dependent choices.
- Reuse related open PRs; create PRs ready for review, never draft. Workflows stay manual.

Open PR inventory checked at task start: #64 `feat/app-wide-liquid-glass` at `8bdfbe9e1e583ebf3951cc16e8f56d717d68e5dc`, plus dependency PRs #63, #62, #61, #55, #54, #43. None covers receipts. #64 remains separate and unmerged; this task does not validate its rendering or fix its outstanding review findings.

## Receipt diagnosis and implementation

Source inspection of both the base and `v0.0.8` found:

1. `SeenTick.sendBlueTickMsg` queued an explicitly flagged read job before updating pending hidden-message rows to `viewed=true`. A worker could reach downstream guards before authorization was visible.
2. `HideSeen.hookEnforceHiding`, the direct incoming-message guard added in the previous upstream port, checked privacy flags without honoring a previously authorized/viewed record.
3. The modern dispatch hook suppressed an entire dispatch based on any unviewed hidden row or Hide Seen setting. The protocol hook also returned null for any old unviewed row, independent of receipt type. These broad rules could suppress delivery even when only Hide Read was selected.
4. Outgoing-job discovery used `Contains` for SendE2EMessageJob. Reply handling could route a chat reply into the visible status branch based on stale `currentScreen`.

Changes on this branch:

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

## Pending migration program for a new instance

The user requested a new-instance migration prompt. This current PR implements fixes; the migrations below remain **unimplemented**. Begin by refreshing origin/upstream refs and reading this branch/PR: carry the receipt fix forward whether it has merged or remains open. Audit commits newer than the pinned upstream head rather than assuming the snapshot is still latest. Reuse related migration PRs if present; otherwise open ready-for-review migration PRs linked to this task.

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
- **Liquid Glass #64** remains its own open work. It is outside the receipt/foundation migration PR; preserve its branch and review state when planning cross-cutting framework conversions.

## Conversation log

- 2026-10-06, Codex: fetched current Community and original-upstream histories; recorded the X API block. User supplied installed versions and corrected Hide Delivered to enabled. User selected compatible fixes now, new features/migrations separately, then explicitly requested migration state and a startup prompt for a new instance. Implemented the fixes and validation above. Published PR #65, ready for review. The remote implementation tree was verified to match the locally validated tree (`dac5266ed21bfeec25a36c1f769f35005cb49dc6`).
