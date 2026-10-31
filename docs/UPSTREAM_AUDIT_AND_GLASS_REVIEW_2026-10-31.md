# Upstream synchronization and conversation-glass review — 2026-10-31

## Scope, provenance, and conclusions

This is a **source audit**, not a claim that the candidate hooks work on the Galaxy S25 Ultra. It compares these immutable public snapshots:

| Repository | Snapshot | Relation to Community | Recent activity and evidence |
| --- | --- | --- | --- |
| [Community](https://github.com/igorcv88/WaEnhancerXCommunity) | [`380b475e`](https://github.com/igorcv88/WaEnhancerXCommunity/commit/380b475e77bee727b0715bc5a9eca683f0bdaae4), master after merged #106 | Review and PR base | #102–#106 glass changes merged; #106 has one still-unresolved inline review finding. |
| [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) | [`36c22aed`](https://github.com/Dev4Mod/WaEnhancer/commit/36c22aeda030327704b5ee0b099f060fd42c1120) | Original WaEnhancer code ancestry; independent Kotlin/Yuki runtime now | **77 commits ahead** of the exact endpoint of the [2026-10-06 audit](UPSTREAM_AUDIT_2026-10-06.md), `749c3cae`; compare is ancestor-linear (`ahead_by=77, behind_by=0`). Last functional changes 2026-10-28/29 UTC. |
| [WaEnhancerX/WaEnhancerX](https://github.com/WaEnhancerX/WaEnhancerX) | [`8062ecda`](https://github.com/WaEnhancerX/WaEnhancerX/commit/8062ecda7c821bcfd6c794e6d55a25c6e5156be7), main, v2.0.5 | **Closest source and package architecture**, but diverged substantially | Last visible commits 2026-10-23; active feature/hook, DexKit, preferences, receipt and lifecycle refactors. No common current merge-base was established; **do not label its changes as N commits ahead of Community**. |
| [mubashardev/WaEnhancer](https://github.com/mubashardev/WaEnhancer) | [`e3614d70`](https://github.com/mubashardev/WaEnhancer/commit/e3614d70e609b65eb77592fab21f040403d15ab5), main | Accessible standalone public snapshot, not the current WaEnhancer X implementation | Exactly one public commit in the inspected history, dated 2026-10-29: GPL-3.0 restructuring and removal of external API bridges. Its small skeleton does **not** contain the functional glass/hook code sought here. It is not evidence of a new X fix. |

The previous audit could not access the X lineage because the older URL returned 451. The maintained `WaEnhancerX/WaEnhancerX` organization now supplies a usable source snapshot; the similarly named `mubashardev/WaEnhancer` **must not be confused with it**. Git comparisons are meaningful only with a verified shared ancestry. All code descriptions below were checked against repository source and individual commit diffs, not release notes alone.

**Core finding:** none of the three upstream source snapshots includes `GlassPane`, `ConversationGlassPanes`, `GlassMaterialDrawable`, `LiquidLens`/OneMate, `voice_note_stub` or the app's conversation-voice Liquid Glass capture. In particular, the X upstream's *call recording* hooks are not hooks for the in-chat *voice-note recorder*. They cannot directly repair first-frame voice glass, the red mic position, reply/voice geometry, or Canvas-painted speech-bubble glass. Those need Community-specific evidence and narrow hook fixes.

## Priority plan and exact disposition

| Priority | Candidate | Source | Why it matters | Decision for this PR |
| --- | --- | --- | --- | --- |
| P0 | Release recycled Canvas-owned message-bubble bindings when the native row no longer draws the adopted factory | [Community #106 review](https://github.com/igorcv88/WaEnhancerXCommunity/pull/106) | Old owner blocks the replacement and retains text recolouring and capture budget | **Implemented**: identity-based drawing ledger; check after outer row draw, release on next UI turn, attempt measured `Fyn` row adoption, defer unmeasured rows. Must verify on device. |
| P1 | Replace per-invalidation filtering with attach/visibility hooks | [Dev4Mod `dcda601`](https://github.com/Dev4Mod/WaEnhancer/commit/dcda601), [`b0a8681`](https://github.com/Dev4Mod/WaEnhancer/commit/b0a8681) | Community `Others.filterItems` previously hooked `View.invalidate(boolean)` for **every invalidation** while a filter was configured, exactly in a renderer-sensitive process | **Implemented an adapted Java equivalent**: onAttach once + `View.setFlags` visibility mask, bounded fallback to `View.setVisibility`. Existing JSON `gone` policy retained; no Kotlin runtime migration. |
| P1 | Immediately recognize recorder action, then sample its native layout epoch without timer dependency | Community #104–#106 logs/inspection | The named signal exists, but native `voice_note_stub` can remain short for 0.8–4 seconds; the compositor presently validates the measured panel, not the physical button down | **Investigate, not blindly patch**. Capture exact onset, visibility events, measured width/height, first `GlassPane` presentation; do not guess a 140dp hit area or move WhatsApp's controls. |
| P1 | Correct speech-bubble actual paint ownership and mask | Community #103–#106 | Wrapped factory sometimes has no `Drawable.Callback` view; `Fyn` row background and Canvas draws can conceal a successful GPU capture | Partial rebind fix here; **not visually accepted**. Instrument paint ownership and distinguish `RECORDED` from `PRESENTING_LIVE` and pixels displayed. |
| P1 | Versioned DexKit method/field descriptors with negative lookup cooldown | [X `76d85e1`](https://github.com/WaEnhancerX/WaEnhancerX/commit/76d85e1), [`45bb199`](https://github.com/WaEnhancerX/WaEnhancerX/commit/45bb199) | Repeated rescans and stale obfuscation descriptors can produce wrong hooks or startup stalls | **Proposed follow-up** with migration tests, not copied wholesale into legacy `Unobfuscator`. |
| P1 | Bounded, leased lazy DexKit bridge | [Dev4Mod `c0cfdd3`](https://github.com/Dev4Mod/WaEnhancer/commit/c0cfdd3) | Cached startup need not parse APK; idle bridge can close after 30s; avoids repeated cold startup work | **Proposed** after timing instrument and reentrant/close tests; different KavaRef integration. |
| P1 | Prevent premature unhook on `onStop`; keep hooks until Activity destruction | [X `81a46f1`](https://github.com/WaEnhancerX/WaEnhancerX/commit/81a46f1) | Avoids lost observers across screen transitions | **Mostly already covered** by Community `STOP` vs `DESTROYED` screen lifecycle; audit `FeatureRegistry` separately, do not reapply X's unhook ownership manager automatically. |
| P2 | Cache best-match reflection lookups | [Dev4Mod `549218e`](https://github.com/Dev4Mod/WaEnhancer/commit/549218e) | Removes repeated traversal/parameter scoring | **Candidate**: benchmark call sites first; Community already caches declared methods by class and XposedHelpers has its own caches. |
| P2 | Stable floating bar geometry and scroll padding | [Dev4Mod `84c9c81`, `9b26a67`](https://github.com/Dev4Mod/WaEnhancer/commit/84c9c81), [X `7441919`](https://github.com/WaEnhancerX/WaEnhancerX/commit/7441919) | Improves tab transitions and the list's bottom clearance | **Compare** against Community's custom floating glass renderer and measured inset contracts. The approved floating bar must not be changed opportunistically. |
| P2 | Support 2.26.39–2.26.41; X additionally lists 2.26.42 | [Dev4Mod `cc51ac7`](https://github.com/Dev4Mod/WaEnhancer/commit/cc51ac7), [X `5056aa0`](https://github.com/WaEnhancerX/WaEnhancerX/commit/5056aa0) | Community catalog currently ends at **2.26.38.xx** for regular WhatsApp | **Do not just append version strings**. First test critical resolvers and identity-preserving hooks on APK/DEX for each version; use user opt-in to bypass versions meanwhile. |
| P2 | Receipt batch authorization & sender identity | [Dev4Mod `d2c71a5`](https://github.com/Dev4Mod/WaEnhancer/commit/d2c71a5), [X `9dee603`](https://github.com/WaEnhancerX/WaEnhancerX/commit/9dee603) | DB and queue work, LID/PN ambiguity, background responses | **Candidate**, conditioned on existing Community `HideSeen`/`SeenTick` DB schema and preserving the user's per-contact privacy guarantees. Never mix queue success with remote receipt acknowledgment. |
| P3 | R8/ProGuard, Kotlin/Yuki/KavaRef/API101 migration | [Dev4Mod `4909f00`, `570d0e9`, `2f7ea49`](https://github.com/Dev4Mod/WaEnhancer/commit/4909f00), [X `585eefc`, `d47d40f`](https://github.com/WaEnhancerX/WaEnhancerX/commit/585eefc) | Packaging, API upgrades, smaller APKs | **Independent project**. Current injected classes, resource names and single app-only entrypoint must survive minification. Explicitly avoid migrating frameworks in a voice-glass hotfix. |
| Out of scope | Root/scope/system-provider hooks, bootloader spoofing, keyboxes, Play Integrity bypass, new privileged restart flows | Dev4Mod `1ef4e19`, X `6194297`, `54d907b`, `6f6115a` | Their architecture assumes different privileges and targets | **Do not port**: Community app-only boundary prohibits system-process hooks, broad IPC capabilities and privileged command expansion. |

### Direct app-side hook improvements

1. **`View.invalidate(boolean)` removal** is the best low-risk upstream port. Original code at `Others.filterItems()` iterated all invalidations and used a `ThreadLocal` recursion guard, even though only a tiny ID whitelist needed `GONE`. New code uses a constant-time ID lookup only when a view attaches or changes its visibility flags. An unrecognized filter behaviour remains ignored, matching the previous implementation. Its benefit depends on `filter_items` being configured; no universal FPS gain is claimed.
2. **Factory draw identity matters.** A view can draw both a quote and a message factory; retaining only the last drawn `Drawable` could falsely evict one. `NativeBubbleDrawLedger` compares actual object identities and supports several factories. The outermost native row `draw(Canvas)` completes before the deferred cleanup runs, so no background mutation occurs inside the WhatsApp drawing stack. An idle but attached row is not considered stale merely because no time-based heartbeat occurred.
3. **The cleanup is not an optical fix.** It frees a stale `Binding`, releases its `LiveBudget` slot, restores original text colours and tries native row adoption. If the platform draws a `Fyn` mask over the glass in a later Canvas phase, source and draw-order evidence is still required. We deliberately do not swap all conversation rows to translucent pills; that would lose native tap/mask semantics and repeat the opaque fallback problem.

## Maintained X: high-value recent changes and fit

The X codebase currently has a different implementation lifecycle, UI (Compose), versioned typed DexKit cache and more diagnostic contracts. These are selected *source-verified* candidates, **not an assertion of implementation in Community**:

| Commit(s) | Actual code path | Incorporation assessment |
| --- | --- | --- |
| [`9cffa1f`](https://github.com/WaEnhancerX/WaEnhancerX/commit/9cffa1f), [`81a46f1`](https://github.com/WaEnhancerX/WaEnhancerX/commit/81a46f1) | `HookRegistrar` retains `XC_MethodHook.Unhook` by owning `BaseFeature`; `FeatureRegistry` releases lazy hooks at destroy, not stop | Strong lifecycle model; however installing it globally would change the single-entrypoint hook manager and adds stack-inspection fallback. Extract a narrowly owned lifecycle contract only after tests. |
| [`76d85e1`](https://github.com/WaEnhancerX/WaEnhancerX/commit/76d85e1) | `DexCacheManager`: typed JSON class/method/field descriptors, schema + host-module version stamps, single-flight per key, 60s bounded negative cache; stale entries re-resolve | Highest potential for obfuscation stability; rework Community resolvers, not a class copy. Preserve result uniqueness and invalidation on *WhatsApp* updates. |
| [`585eefc`](https://github.com/WaEnhancerX/WaEnhancerX/commit/585eefc) | `MessageDeviceSourceHook` and `AntiRevokeHook` resolve concrete adapter `getView` overloads, plus ProGuard debug changes | Port the typed resolver strategy only where Community has matching adapter signatures. Avoid broad reflection matching on the UI thread. |
| [`28d811f`](https://github.com/WaEnhancerX/WaEnhancerX/commit/28d811f) | `ChatLimitsBypassHook` fallback pathways for regular WhatsApp pin/chat limits | Candidate for supported-version updates; add tests for *real* WA and Business method shapes. |
| [`7441919`](https://github.com/WaEnhancerX/WaEnhancerX/commit/7441919), [`f8b51c4`](https://github.com/WaEnhancerX/WaEnhancerX/commit/f8b51c4), [`7877af4`](https://github.com/WaEnhancerX/WaEnhancerX/commit/7877af4) | `FloatingBottomBarHook`: content padding for scroll lists; icon selected/unselected colours; wrapper-specific outline/shadow | Visual/interaction candidates. Community's floating navbar glass is user-approved; port only a verified scroll-inset bug, not visual defaults. |
| [`886f066`](https://github.com/WaEnhancerX/WaEnhancerX/commit/886f066), [`0146ce3`](https://github.com/WaEnhancerX/WaEnhancerX/commit/0146ce3), [`9dee603`](https://github.com/WaEnhancerX/WaEnhancerX/commit/9dee603), [`13a28ce`](https://github.com/WaEnhancerX/WaEnhancerX/commit/13a28ce) | Status player receipt routing, missing-key fallback, bounded hidden-read store and status icon resolution | Useful for the historic blue-tick-on-reply regression. Require strict status/chat identity tests to avoid releasing the wrong contact. |
| [`e7774c1`](https://github.com/WaEnhancerX/WaEnhancerX/commit/e7774c1), [`29052b7`](https://github.com/WaEnhancerX/WaEnhancerX/commit/29052b7) | IPC preferences: pending edits survive refresh/commit, startup handles unavailable XSharedPreferences snapshots | Candidate for bridge reliability; Community uses a different authenticated direct-provider IPC. Avoid restoring public access to private preferences. |
| [`45bb199`](https://github.com/WaEnhancerX/WaEnhancerX/commit/45bb199), [`c0efbfb`](https://github.com/WaEnhancerX/WaEnhancerX/commit/c0efbfb) | Negative lookup retry and Application.onCreate fallback via Instrumentation | Model resilience to renamed hooks, but never hook Android system processes or introduce repeated expensive startup scans. |
| [`5e94145`](https://github.com/WaEnhancerX/WaEnhancerX/commit/5e94145), [`61c597c`](https://github.com/WaEnhancerX/WaEnhancerX/commit/61c597c), [`c5fae36`](https://github.com/WaEnhancerX/WaEnhancerX/commit/c5fae36) | **Call recording** event contract, bounded capture lifetime, cleanup after stop errors | Not the conversation voice-note recorder. Still a good lesson: own state transitions and teardown by native events. |
| [`5056aa0`](https://github.com/WaEnhancerX/WaEnhancerX/commit/5056aa0), [`f8e7fa6`](https://github.com/WaEnhancerX/WaEnhancerX/commit/f8e7fa6) | Catalog support 2.26.42 and explicit warning for unsupported manually allowed versions | Good UX; compatibility remains a tested hook property, not a string list. |
| [`e9bf00d`](https://github.com/WaEnhancerX/WaEnhancerX/commit/e9bf00d), [`134160a`](https://github.com/WaEnhancerX/WaEnhancerX/commit/134160a), [`c258cc2`](https://github.com/WaEnhancerX/WaEnhancerX/commit/c258cc2), [`82cb076`](https://github.com/WaEnhancerX/WaEnhancerX/commit/82cb076) | Redacted exception diagnostics and rejection of ambiguous recipient IDs | High value in production telemetry; the Termux protocol below never collects message text/audio. |
| [`6a16598`](https://github.com/WaEnhancerX/WaEnhancerX/commit/6a16598), [`79599a3`](https://github.com/WaEnhancerX/WaEnhancerX/commit/79599a3) | Duplicate-safe status downloads and dynamic menu refresh on swipe | Optional user-facing enhancements, not glass remediation. |
| [`562a2b2`](https://github.com/WaEnhancerX/WaEnhancerX/commit/562a2b2), [`7496720`](https://github.com/WaEnhancerX/WaEnhancerX/commit/7496720), [`2a672f4`](https://github.com/WaEnhancerX/WaEnhancerX/commit/2a672f4) | Timestamp formats; granular Meta AI removal controls | Product backlog only. |

No upstream X voice-note Liquid Glass renderer or presentation-ready `Fyn` adapter was located. A rewrite of Community's bespoke graphics into upstream X classes would therefore be an **untested architecture migration**, not a bug fix.

## Original Dev4Mod: 77-commit ledger since the prior audit

All commits below are in [`749c3cae...36c22aed`](https://github.com/Dev4Mod/WaEnhancer/compare/749c3cae4a1f1cffc2572a4a2365517ba6ce0322...36c22aeda030327704b5ee0b099f060fd42c1120) (77 total, **74 non-merge commits** listed below). `A` = adapted here, `I` = investigate compatibility/benefit, `D` = defer/not applicable, `P` = policy/framework migration requiring separate work. These are triage decisions, not a claim of tests on 2.26.41.

| Commit | Change | Route |
| --- | --- | --- |
| [`36c22ae`](https://github.com/Dev4Mod/WaEnhancer/commit/36c22ae) | Changelog 1.6.2 | D |
| [`1a06ff0`](https://github.com/Dev4Mod/WaEnhancer/commit/1a06ff0) | Locked chats: single `HashSet`, remove matched LID/PN contacts | I; Community prior semantics may overlap |
| [`d2c71a5`](https://github.com/Dev4Mod/WaEnhancer/commit/d2c71a5) | Batch pending blue ticks, newest 50, transactional viewed update | I; DB/queue privacy |
| [`e08a833`](https://github.com/Dev4Mod/WaEnhancer/commit/e08a833) | Avoid rebuilding Instagram-style status header per update | I; scroll performance |
| [`a0e1c89`](https://github.com/Dev4Mod/WaEnhancer/commit/a0e1c89) | Reduce hot theme, CustomView and resource ID work | I |
| [`847bba6`](https://github.com/Dev4Mod/WaEnhancer/commit/847bba6) | Start bridge Activity only after first connection failure | I; Community has different bridge |
| [`5d1b37a`](https://github.com/Dev4Mod/WaEnhancer/commit/5d1b37a) | Prefetch theme wallpaper before first Home frame | I; initial frame, avoid stale capture |
| [`6813a52`](https://github.com/Dev4Mod/WaEnhancer/commit/6813a52) | Precompile device-suffix JID regex | I; cheap targeted port |
| [`549218e`](https://github.com/Dev4Mod/WaEnhancer/commit/549218e) | Cache best-match reflection method lookups | I; first profile call sites |
| [`b0a8681`](https://github.com/Dev4Mod/WaEnhancer/commit/b0a8681) | Filter hidden items via View attach/flags, not invalidate | **A** |
| [`dcda601`](https://github.com/Dev4Mod/WaEnhancer/commit/dcda601) | Shared View lifecycle/flags/measure dispatcher | **A** subset; dispatcher framework D |
| [`c0cfdd3`](https://github.com/Dev4Mod/WaEnhancer/commit/c0cfdd3) | Lazy leased DexKit with 30s idle close | I |
| [`39907bc`](https://github.com/Dev4Mod/WaEnhancer/commit/39907bc) | DexKit official 2.3.0 artifact | P |
| [`82c7a50`](https://github.com/Dev4Mod/WaEnhancer/commit/82c7a50) | Changelog | D |
| [`efdd903`](https://github.com/Dev4Mod/WaEnhancer/commit/efdd903) | Forward limit: typed field; remove groups/channel blocks | I |
| [`01e2d5a`](https://github.com/Dev4Mod/WaEnhancer/commit/01e2d5a) | Changelog | D |
| [`7657269`](https://github.com/Dev4Mod/WaEnhancer/commit/7657269) | Xposed engine recommendation | D |
| [`9b68b21`](https://github.com/Dev4Mod/WaEnhancer/commit/9b68b21) | Original's 1.6.2 version values | D |
| [`b84f5f6`](https://github.com/Dev4Mod/WaEnhancer/commit/b84f5f6) | `LayoutInflater`-based conversation view overload resolution | I; verify WA DEX signatures |
| [`df141fe`](https://github.com/Dev4Mod/WaEnhancer/commit/df141fe) | Suppress missing Flyme method error | I; host variation |
| [`ebc7d33`](https://github.com/Dev4Mod/WaEnhancer/commit/ebc7d33) | AssemblyAI translated key label | D |
| [`bc5029d`](https://github.com/Dev4Mod/WaEnhancer/commit/bc5029d) | Magisk DenyList diagnostics/fix | D; managerless KSUD environment differs |
| [`509c4ec`](https://github.com/Dev4Mod/WaEnhancer/commit/509c4ec) | Text status creator `onCreate(View,Bundle)` either order | I |
| [`7c4d140`](https://github.com/Dev4Mod/WaEnhancer/commit/7c4d140) | ProGuard preserve runtime class/member names | P |
| [`bbe935a`](https://github.com/Dev4Mod/WaEnhancer/commit/bbe935a) | Changelog | D |
| [`790a7e2`](https://github.com/Dev4Mod/WaEnhancer/commit/790a7e2) | Inline-safe pinned chat limit bypass | I |
| [`bea484a`](https://github.com/Dev4Mod/WaEnhancer/commit/bea484a) | Changelog | D |
| [`1abe476`](https://github.com/Dev4Mod/WaEnhancer/commit/1abe476) | Anti-disappearing via WhatsApp msgstore connection | I; DB safety |
| [`3b50e84`](https://github.com/Dev4Mod/WaEnhancer/commit/3b50e84) | Changelog | D |
| [`cc51ac7`](https://github.com/Dev4Mod/WaEnhancer/commit/cc51ac7) | WA/Business version 2.26.41 declarations | I; no blind enable |
| [`5db954a`](https://github.com/Dev4Mod/WaEnhancer/commit/5db954a) | Sticker colored outline: extra Rect argument | I; host signature |
| [`1ef4e19`](https://github.com/Dev4Mod/WaEnhancer/commit/1ef4e19) | SettingsProvider system scope/visibility bypass | **D** app-only boundary |
| [`8db37f4`](https://github.com/Dev4Mod/WaEnhancer/commit/8db37f4) | Per-contact privacy keyed by LID | I; old migration needed |
| [`e5c347e`](https://github.com/Dev4Mod/WaEnhancer/commit/e5c347e) | Preload statuses without opening Status tab | I; potentially valuable, costs/permissions |
| [`42f1632`](https://github.com/Dev4Mod/WaEnhancer/commit/42f1632) | Original release automation & mappings | D; manual Actions contract |
| [`2f7ea49`](https://github.com/Dev4Mod/WaEnhancer/commit/2f7ea49) | R8 obfuscation/resource shrink | P |
| [`570d0e9`](https://github.com/Dev4Mod/WaEnhancer/commit/570d0e9) | YukiHookAPI 1.5 official dependency | P |
| [`3d01fad`](https://github.com/Dev4Mod/WaEnhancer/commit/3d01fad) | Native message scheduling and View Once text | I; feature scope separate |
| [`01a8bb7`](https://github.com/Dev4Mod/WaEnhancer/commit/01a8bb7) | AntiRevoke result return-type checks | I; prevent host crashes |
| [`2fc180b`](https://github.com/Dev4Mod/WaEnhancer/commit/2fc180b) | Unknown-number delivered receipt privacy | I; policy impact |
| [`2d2301a`](https://github.com/Dev4Mod/WaEnhancer/commit/2d2301a) | Channel header, call and locked chat resolvers | I |
| [`f2551e7`](https://github.com/Dev4Mod/WaEnhancer/commit/f2551e7) | Abstract preference-wrapper method tolerance | I; provider compatibility |
| [`f4a8da1`](https://github.com/Dev4Mod/WaEnhancer/commit/f4a8da1) | JDK21 compiler build | P; no CI in this PR |
| [`0e4f586`](https://github.com/Dev4Mod/WaEnhancer/commit/0e4f586) | Changelog | D |
| [`090d65a`](https://github.com/Dev4Mod/WaEnhancer/commit/090d65a) | Deleted-message viewer and backup capture | I; user data/privacy |
| [`cdff7a9`](https://github.com/Dev4Mod/WaEnhancer/commit/cdff7a9) | Contact picker uses address book and LID JIDs | I |
| [`8ec2216`](https://github.com/Dev4Mod/WaEnhancer/commit/8ec2216) | Hide My App List fix and whitelist mode | D; platform-specific root policy |
| [`015d446`](https://github.com/Dev4Mod/WaEnhancer/commit/015d446) | Ghost mode live; don't rewrite read job type 89 | I; receipts |
| [`2a55b5b`](https://github.com/Dev4Mod/WaEnhancer/commit/2a55b5b) | Dedicated resolver/prop feature | I |
| [`4909f00`](https://github.com/Dev4Mod/WaEnhancer/commit/4909f00) | Yuki/libxposed API101 migration | P |
| [`84c9c81`](https://github.com/Dev4Mod/WaEnhancer/commit/84c9c81) | Floating bar base padding and ignore nav inset | I; compare approved glass bar |
| [`107373a`](https://github.com/Dev4Mod/WaEnhancer/commit/107373a) | Changelog | D |
| [`166c62b`](https://github.com/Dev4Mod/WaEnhancer/commit/166c62b) | Emoji span centering | I |
| [`e6f8389`](https://github.com/Dev4Mod/WaEnhancer/commit/e6f8389) | Preferences through SettingsProvider | D; app-only authenticated IPC differs |
| [`9b26a67`](https://github.com/Dev4Mod/WaEnhancer/commit/9b26a67) | Floating bar height stable across tabs | I |
| [`38ecb66`](https://github.com/Dev4Mod/WaEnhancer/commit/38ecb66) | Receipt privacy checks/refactor | I |
| [`c4813e1`](https://github.com/Dev4Mod/WaEnhancer/commit/c4813e1) | Theme drawable resolution and superclass tolerance | I |
| [`ed39fc4`](https://github.com/Dev4Mod/WaEnhancer/commit/ed39fc4) | Custom-list control with filters enabled | I |
| [`1da24d7`](https://github.com/Dev4Mod/WaEnhancer/commit/1da24d7) | Changelog | D |
| [`5d63769`](https://github.com/Dev4Mod/WaEnhancer/commit/5d63769) | Additional WA/Business version array entries | I; superseded by 41 |
| [`5d0398f`](https://github.com/Dev4Mod/WaEnhancer/commit/5d0398f) | Preference transport and provider changes | P; boundary review |
| [`6fa60ac`](https://github.com/Dev4Mod/WaEnhancer/commit/6fa60ac) | ChatFilter/Unobfuscator string match adjustments | I |
| [`94975f2`](https://github.com/Dev4Mod/WaEnhancer/commit/94975f2) | Video transcoder method hook resolution | I |
| [`eda42f9`](https://github.com/Dev4Mod/WaEnhancer/commit/eda42f9) | Profile photo method arg handling | I |
| [`02e4fc5`](https://github.com/Dev4Mod/WaEnhancer/commit/02e4fc5) | Ghost-mode resolver argument signatures | I |
| [`fa48d81`](https://github.com/Dev4Mod/WaEnhancer/commit/fa48d81) | Disable document scanner pref | I |
| [`ae736f3`](https://github.com/Dev4Mod/WaEnhancer/commit/ae736f3) | ML Kit service metadata Android 14+ | I; old port overlap |
| [`91088e3`](https://github.com/Dev4Mod/WaEnhancer/commit/91088e3) | User-facing missing-picker error | I |
| [`91ba9fc`](https://github.com/Dev4Mod/WaEnhancer/commit/91ba9fc) | Gradle daemon JVM property removal | D |
| [`656349e`](https://github.com/Dev4Mod/WaEnhancer/commit/656349e) | Changelog | D |
| [`f4cc903`](https://github.com/Dev4Mod/WaEnhancer/commit/f4cc903) | Group loading in contact picker | I |
| [`73d1c4e`](https://github.com/Dev4Mod/WaEnhancer/commit/73d1c4e) | ML Kit registrar metadata | I; prior port overlap |
| [`8b71532`](https://github.com/Dev4Mod/WaEnhancer/commit/8b71532) | Bridge-connection toast removal | I |
| [`10d49b7`](https://github.com/Dev4Mod/WaEnhancer/commit/10d49b7) | FeatureLoader init order and missing-module message | I |

Merge commit details and commit accounting remain available from the compare URL above. The previous 55-commit ledger is not repeated in this file.

## PR review: #102–#106 and current blockers

| PR / inline comment | Status against `master@380b475e` | Action |
| --- | --- | --- |
| [#102, `4428714959`](https://github.com/igorcv88/WaEnhancerXCommunity/pull/102) | Already fixed in later merged #105: nested wrapper replacement adopted after removing the old binding | No duplicate change. |
| [#103](https://github.com/igorcv88/WaEnhancerXCommunity/pull/103) | No inline review threads returned | Reviewed functional history; physical acceptance absent. |
| [#104, `4429261622`](https://github.com/igorcv88/WaEnhancerXCommunity/pull/104) | Already fixed in later merged #105: pending unmeasured native rows retained | No duplicate change. |
| [#105, `4429378942`](https://github.com/igorcv88/WaEnhancerXCommunity/pull/105) | Outdated; #105 removed `FLAG_FULLSCREEN` and hid status bar through `WindowInsetsController` to preserve IME resize | No duplicate change. |
| [#106, `4429718958`](https://github.com/igorcv88/WaEnhancerXCommunity/pull/106) | **Valid and still unresolved:** `canvasOwned` is sticky, no redraw lifecycle verification; recycled rows can hold dead bindings | **Addressed by this PR's draw-identity reconciliation.** No device claim. |
| Earlier #73/#84 | Nonzero own-scroll and padded optical test stripe fixes already incorporated in merged #105 per prior audit | No duplicate change. |

### Independent code review of Work's merged #106

1. `AppLiquidGlass.Session.scan()` had a `canvasOwned` case that checked only `glass.isCanvasOwnedBy(target)`, which is the previously stored owner pointer, **not proof it still paints on the row**. This directly explains the #106 review and one route for intermittent bubbles. The fix is event-driven, without adding a polling loop or opaquely replacing a message's mask.
2. `GlassMaterialDrawable.captureBehind()` can return `RECORDED` without user-visible pixels. A later `PRESENTING_TRANSPARENT` says the drawable had no active live presentation on that draw. Further, native Canvas factories may render over the view background after it; a successful capture is **not visual acceptance**.
3. `ConversationGlassPanes.onRecorderSignal()` currently posts an animation invalidation; it does not synchronously commit a correctly measured recorder pane or prove that the native recorder is actually visible. `findVoicePanel()`/`signaledVoicePanel()` depend on a measured width, an existing hierarchy and in some cases elapsed-time text. `ComposerTransition` adds a two-frame/80ms quiet policy **after** the initial recording start. The previous logs measured 0.8–4 seconds before the native recorder expanded. Separating **WhatsApp native panel readiness** from **our capture/presentation latency** is essential.
4. `alignRecordingMic()` finds a named small icon and changes **translationY only** relative to the timer, whereas the user wants it **left of the waveform**. The relevant horizontal native positions are not verified. Moving it by a guessed X coordinate could cover delete/pause controls or damage gestures. Record timer/waveform/mic bounds, then update narrowly.
5. The fullscreen header's `WallpaperUnderlay` provides wallpaper in the former status-bar strip; `floatHeader`/list top padding alone do not prove that WhatsApp's list paints through a newly exposed top region. Need native list clip/window inset evidence before stretching the list further. The approved normal input geometry and search/floating navbar must be preserved.
6. The bubble OneMate look differs from the user's reference. A correctly routed factory still needs separate optical tuning for speech-bubble radius/tint/blur, with incoming/outgoing custom colour and contrast. Do **not** tweak global rim/refraction now: the user explicitly approved the app surface aesthetics and floating bar.
7. Per-view `Fyn` masking, `LiveBudget` and `PresentationRetry` potentially multiply GPU work during fast scroll; software/Gradle tests cannot establish 120 Hz acceptance. Avoid increasing capture budget or reintroducing opaque static fallback while diagnosing.

### Minimum physical acceptance (next build)

Use the same chat, wallpaper, WhatsApp version and enabled surfaces across tests. Run: (1) fresh opening, type/reply and cancel; (2) begin/cancel voice note on an **untouched** screen; (3) prepare a reply then begin/cancel voice; (4) scroll 30+ incoming/outgoing text/photo/quote messages with BUBBLES scopes toggled independently; (5) navigate away/back; (6) open keyboard and confirm IME resize/fullscreen status-bar state. Require one native quote, red microphone beside the waveform, dynamic background all the way to the top, `PRESENTING_LIVE` for visible bubbles, and no green/grey static fallback or capture-budget flicker. A screenshot and frame trace are as important as `GlassState` logs.

For performance, compare **same exact gestures** with glass off/on: frame p50/p90/p99 and GPU time via `dumpsys gfxinfo com.whatsapp framestats`, capture/presentation latency keyed by event rather than wall-clock guesses, and `SurfaceFlinger` jank if available. Exclude old PIDs; avoid logging message text, recipient numbers, message IDs or audio payloads. See the accompanying Termux block in the PR handoff message.

## Implementation and validation limits for this PR

- Scope of code changes: `AppLiquidGlass`, `GlassMaterialDrawable`, new pure-JVM `NativeBubbleDrawLedger` and tests, and `Others.filterItems`. No AGSL, optical coefficients, preference semantics, release workflow, system hooks, app ID or native recording controls changed.
- Draw identity is recorded under the already scoped native row-draw hooks. Each stale callback is de-duplicated per row and deferred to the next UI turn. `Fyn` row fallback rebind is only attempted on a verifiable native message row. No periodic invalidations or no-glass fallback introduced.
- Local checks: all six edited/new Java files passed JDK 17 compiler **syntax parsing only**; standalone bubble-identity smoke passed; `tools/test_conversation_glass.py` passed synthetic recorder transition checks; four app-only runtime tests passed; `git diff --check` clean. Full Android compilation/JUnit requires SDK36/JDK21/Gradle, unavailable in this session.
- These are plausible fixes for an observed race and a specific documented hot hook, **not a verified repair** of the intermittent bubbles, recording delay, microphone, status-bar strip, or visual style. The rest need physical evidence before another code iteration.
- Community's app-only IPC and lifecycle invariants and single LSPosed entrypoint remain binding. External projects' GPL/Apache license headers and attribution must be respected if more code is ported in future.
