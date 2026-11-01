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

## Verification and porting handoff — 2026-11-01

The historical sections above describe the **2026-10-31 audit snapshot and PR #107**, not the current master or a recommendation to replay PR #107. GitHub commit comparisons were repeated on 2026-11-01:

| Source | Audited SHA vs current default-branch HEAD | Additional commits to audit |
| --- | --- | --- |
| `Dev4Mod/WaEnhancer` | `36c22aeda030327704b5ee0b099f060fd42c1120` = `HEAD` | **0** |
| `WaEnhancerX/WaEnhancerX` | `8062ecda7c821bcfd6c794e6d55a25c6e5156be7` = `HEAD` | **0** |
| `igorcv88/WaEnhancerXCommunity` | prior `380b475e77bee727b0715bc5a9eca683f0bdaae4` → merged `f77a634` (#120) | **36**, including merges and Community-specific glass changes; these are *not* new upstream commits |

The original Dev4Mod ledger remains complete for its **77-commit** interval (74 non-merges above; three merges via the compare URL). The maintained X section is a **selected high-value change assessment**, **not** an exhaustive X commit ledger, because its ancestry with Community was not established. A full porting pass must inspect X's remaining commits/files separately and record explicit implemented/adapted/deferred/rejected dispositions rather than assuming this table lists everything. The older [`UPSTREAM_AUDIT_2026-10-06.md`](UPSTREAM_AUDIT_2026-10-06.md) covers Dev4Mod changes before `749c3cae`; its findings must be cross-checked against current code before replay.

Recent Community integration has changed the destination code considerably: #107–#119 were merged for row-mask ownership, recorder and input transitions, wallpaper, legacy recorder indicators and date instrumentation; #120 merged a manual signed PR-preview workflow. The Galaxy user has confirmed the recorder/input and unwanted red-mic/black-strip corrections, but **message-bubble live presentation, sticky date placement and reply surfaces/foreground colours remain under a separate Work implementation request**. On this check, no related Work PR was published. Do not port upstream changes into those active compositor paths until the pending Work diff is available and reconciled; do not reimplement Community fixes based on their old status in this report.

Review-comment cross-check on merged PRs #69–#120:

- Earlier reported own-scroll/padded-optics (#73/#84), unmeasured-row/nested replacement (#102/#104), fullscreen IME (#105), stale canvas rearm (#107), duplicate quote (#108), and repetitive capture-probe sampling (#113) already have subsequent source repairs or tests. An unresolved GitHub thread alone does not mean its bug persists.
- #120's preview suffix `-pr…` is incompatible with the installed-version comparators; the follow-up review branch uses SemVer build metadata `+pr…` so both comparators normalize the preview to the same base release version. This is a preview **name** fix, not proof that every future release's versionCode can upgrade a preview.
- #114's concurrent header/recorder clipping snapshots still conflict in merged master; the follow-up review branch shares original clip flags between the two owners. Physical testing of disable/enable and recorder teardown remains outstanding.
- #118's deferred Canvas factory on row detach, and #115's quote contrast retry, are part of the separately requested bubble/reply lifecycle fix. Do not apply both an old review patch and a Work rewrite blindly; evaluate those precise cases against its final diff and device log first.
- #96's dead spoof controls are already absent from the embedded settings map; #97's Lite Mode emoji font has a narrow bridge fallback; #100's light-background contour got a later contrast-aware path. Do not duplicate these fixes. #91's moving native indicator/floating-bar optics need separate visual regression evidence before changing user-accepted navigation.

### Cloud porting sequence and acceptance

1. Begin from the **then-current merged Community master**; inspect `AGENTS.md`, `ARCHITECTURE.md`, `handoff.md`, both dated upstream audits, and any still-open Work PR. Recompare both source SHAs above against their latest default-branch HEAD before assuming the audit interval is unchanged.
2. For each potentially useful upstream commit, record: source repo + immutable SHA, changed class/resource, current Community equivalent, WhatsApp/Business versions actually supported, compatibility risk, disposition (`already present`, `adapt`, `defer`, `reject`), and the verification needed. Prioritize versioned DexKit descriptor invalidation/negative lookup, bounded expensive reflection/bridge work, native WhatsApp 2.26.39–2.26.42 resolver changes, LID/PN and receipt authorization correctness, and safe preference/cache recovery. These priorities are candidates, not installation evidence.
3. Preserve Community's app-only executable runtime, identity-authenticated IPC, private/public preference boundary, LSPosed entrypoint, device-accepted composer/recorder and floating bar, reversible native View state, and secure data migrations. Do not cherry-pick framework migrations, system-scope hooks, root spoofers or mere compatibility version strings. Port one coherent behavior per commit/PR with source attribution and regression tests.
4. Validate affected resolvers against real WhatsApp and Business APK/DEX versions before enabling new compatibility claims. Run local Java/Gradle tests and build separately; leave manual GitHub Actions untouched unless requested. Treat Galaxy screenshots/logs as mandatory for actual UI/recording/bubble acceptance, and update the audit dispositions after each integration.

This append-only status supersedes the prior report's *current PR* and *pending symptom* wording; it does not change its original commit inventory or historical evidence.

## PR #122 reconciliation and upstream baseline recheck — 2026-11-01

This section supersedes the sentence above that the bubble/reply Work PR was not yet published. [PR #122](https://github.com/igorcv88/WaEnhancerXCommunity/pull/122), `fix/bubble-lifecycle-date-mic-palette`, was published at `2c6d3ecc425f6e3591e18a7dbe1c87212a7cc27f` against `master@f77a634` and independently reviewed. It addresses bubble damage epochs, deferred factory pixel suppression, pending-presentation retries, reversible `date_divider_header` placement, reply-frame NinePatch removal and a single cached colour reference from the microphone. These are **unmerged, not Galaxy-verified** changes. They do not implement any upstream porting candidate.

The automated review of that exact PR commit completed with one valid P2 finding: it could stretch an intentionally inset/sized nested bubble drawable to the whole host bounds. The pending PR #122 follow-up restricts host-size pre-seeding to a wrapper mounted directly as the host background. Independent review also added null guards for a stateful microphone drawable with no current child. Both changes have regression assertions; six existing focused host suites pass. Physical acceptance for message first-presentation/recycling, sticky date position, audio reply corners and mic-matched colours is still outstanding. Preserve the compositor's last confirmed native controls and wallpaper behavior.

[PR #121](https://github.com/igorcv88/WaEnhancerXCommunity/pull/121) independently holds the merged-review follow-ups for #114 header/recorder clip ownership and #120 manual preview version semantics, plus this audit. Both #121 and #122 start from the same master; their `ConversationGlassPanes.java` edits auto-merge in a local `git merge-tree` simulation, while their append-only `handoff.md` entries conflict and must be reconciled manually. The Cloud porting pass should start from the **eventually merged master**, or explicitly base on an integration branch including both reviewed PRs only after their tests pass. Do not reset, overwrite, duplicate or cherry-pick active compositor work.

The two upstream default branch HEADs were rechecked against GitHub on **2026-11-01** and remain exactly `Dev4Mod/WaEnhancer@36c22aeda030327704b5ee0b099f060fd42c1120` and `WaEnhancerX/WaEnhancerX@8062ecda7c821bcfd6c794e6d55a25c6e5156be7`: **zero additional commits** since the prior source ledger. The Dev4Mod 77-commit (74 non-merge) inventory is current for that source snapshot; the maintained X table is deliberately selected rather than exhaustive. A thorough Cloud port must audit all other relevant X commits, compare both repos' latest HEADs at execution time, and cross-check the older `UPSTREAM_AUDIT_2026-10-06.md` before assigning final `already present/adapt/defer/reject` statuses. Do not assume a new WhatsApp version is supported merely because the upstream's allow-list includes it; verify concrete obfuscated methods/fields against actual target DEX/APKs.

## Upstream porting pass — 2026-11-01

### Baseline

- PRs #121 and #122 are merged: `master@f6f15ab` (merge of #122 over `422b4f6`, the #121 merge). The `handoff.md` reconciliation section "PR #122 merge reconciliation with merged #121" is present on master.
- Upstream HEADs re-queried with `git ls-remote` on 2026-11-01: `Dev4Mod/WaEnhancer@36c22aeda030327704b5ee0b099f060fd42c1120` and `WaEnhancerX/WaEnhancerX@8062ecda7c821bcfd6c794e6d55a25c6e5156be7`. Both equal the audited SHAs; **no new upstream commits**.
- WaEnhancerX was audited exhaustively this time: **all 639 non-merge commits** (742 total, 103 merges) have an explicit disposition in [`UPSTREAM_AUDIT_WAEX_LEDGER_2026-11-01.md`](UPSTREAM_AUDIT_WAEX_LEDGER_2026-11-01.md). The selected X table above remains historical context.
- WhatsApp/WhatsApp Business APKs could not be obtained in the cloud session (APKMirror and APKPure return HTTP 403 through the egress proxy). **No resolver in this pass was verified against a 2.26.39–2.26.42 DEX**, and no version was added to the compatibility list.

### Pull requests from this pass

| PR area | Branch | Upstream commits covered |
| --- | --- | --- |
| A. Unobfuscator cache and lookup performance | `fix/unobfuscator-cache-resilience-20261101` | X `76d85e1` (adapted), X `45bb199` (partial), Dev4Mod `a0e1c89` (subset) |
| B. WhatsApp resolver compatibility | `fix/whatsapp-resolver-compat-20261101` | Dev4Mod `efdd903`, `b84f5f6`, `02e4fc5`, `2d2301a`, `f2551e7`, `01a8bb7`, `790a7e2`, `1abe476` (partial) |
| C. Receipts, LID/PN identity | `fix/receipt-identity-ports-20261101` | Dev4Mod `015d446`, `d2c71a5`, `1a06ff0`, `6813a52` |
| D. Audit and handoff (this document) | `docs/upstream-porting-audit-20261101` | — |
| 0. Build fix found during validation | `fix/master-compile-porterduff-20261101` | — (master `f6f15ab` did not compile: hidden `PorterDuffColorFilter.getColor()` from #122) |

Key adaptation decisions:

- **A.** Community keeps its `UnobfuscatorCache` storage layout. A persisted descriptor that no longer decodes is evicted and resolved once more (previously the feature failed until the next WhatsApp update); method/field descriptors now carry return/field type, so an in-place host change of a member's type is treated as stale; empty arrays are encoded explicitly (the old `""` encoding failed permanently); a partially resolvable field map is re-resolved rather than returned partially; concurrent feature threads (up to four) share one DexKit search per key; all getters use one bounded per-process negative cache, cleared by the explicit clear-cache broadcast; a schema stamp invalidates old descriptors once. X's **60-second automatic retry is not adopted**: Community DexKit results are deterministic for one installed APK and loaders are reachable from hook callbacks, so periodic rescans would add main-thread risk without a recovery benefit.
- **B.** Fallbacks never replace a unique legacy match; new anchors require a single match or fall back. `PinnedLimit` uses the Dev4Mod inline-safe hooks only when all anchors exist, otherwise the legacy path; with the chat-pin option off it no longer installs process-wide `ArrayList(int)`/`LinkedHashSet(int)` constructor hooks (they only re-imposed WhatsApp's own limit of 3). The always-on in-chat pinned-message limit (60) is unchanged. AntiRevoke results now follow the hooked method's return type; an unknown type lets WhatsApp proceed instead of raising `ClassCastException`.
- **C.** The type-89 read-job dispatch is no longer rewritten in the modern dispatch path (the job blocks on its ack without timeout); the read job is cancelled in its own hook when Hide Read, Hide Delivered or Ghost Mode applies. The legacy caller path for older builds is unchanged. Bounded blue-tick release keeps Community's authorize-then-enqueue `ReceiptRelease` with rollback for the sent batch; only rows that are older than the newest 50 or no longer resolvable are marked released in one transaction.

### Dev4Mod 74-commit ledger: final dispositions

`ADAPTED` = ported in A/B/C; `ALREADY` = present in Community; `DEFER` = not now (reason); `REJECT` = architecture/policy; `NO-OP` = changelog/translation/version metadata.

| Commit | Final | Reason / Community equivalent |
| --- | --- | --- |
| `36c22ae` | NO-OP | Changelog |
| `1a06ff0` | **ADAPTED (C)** | `LockedChatsEnhancer` single-set fallback; LID/PN/number matching via `JidIdentitySet` (null identities never match; the old number-only match removed every LID-only contact) |
| `d2c71a5` | **ADAPTED (C)** | `PendingReceiptBatch`, `MessageHistory.updateViewedMessagesBatch`, oldest-first query |
| `e08a833` | DEFER | IGStatus caching: Community `IGStatus` differs; FloatingBottomBar part must not alter the approved bar; needs profiling |
| `a0e1c89` | **ADAPTED (A, subset)** | Lock-free `Utils.getID` cache. CustomThemeV2/CustomView/Stickers/MediaPreview parts deferred (different Community code; needs profiling) |
| `847bba6` | ALREADY | Community bridge client never opens `ForceStartActivity` on connect |
| `5d1b37a` | DEFER | Wallpaper prefetch: approved wallpaper path, not changed without device evidence |
| `6813a52` | **ADAPTED (C)** | Precompiled device-suffix pattern in `WppCore` and `FMessageWpp.UserJid` |
| `549218e` | DEFER | Community `ReflectionUtils.callMethod(Method, …)` receives a resolved `Method`; name-based best-match paths use XposedHelpers caches. No hot call site profiled |
| `b0a8681` | ALREADY | Merged #107 |
| `dcda601` | ALREADY (subset) | Merged #107; dispatcher framework not adopted |
| `c0cfdd3` | DEFER | Lazy leased DexKit bridge: requires reordering `Unobfuscator.initWithPath` and close/reopen tests; PR A's single-flight removes duplicate scans first. Needs startup timing on device |
| `39907bc` | DEFER | DexKit artifact change; dependency update is a separate build task |
| `82c7a50` | NO-OP | Changelog |
| `efdd903` | **ADAPTED (B)** | `MultiSelectionLimitArgs` (limit = first int that is not a resource ID; block flags cleared; Community keeps its configurable limit instead of `Int.MAX_VALUE`) |
| `01e2d5a` | NO-OP | Changelog |
| `7657269` | NO-OP | README recommendation |
| `9b68b21` | NO-OP | Version metadata |
| `b84f5f6` | **ADAPTED (B)** | `loadGetViewConversationMethod`: LayoutInflater in any position |
| `df141fe` | REJECT | Downgrade patch hooks were removed from the app-only Community runtime |
| `ebc7d33` | NO-OP | Translation label |
| `bc5029d` | REJECT | Magisk DenyList diagnostics |
| `509c4ec` | ALREADY | Community `TextStatusComposer` already accepts `(Bundle, View)` and `(View, Bundle)` |
| `7c4d140` | DEFER | ProGuard keep rules: Community release is not minified |
| `bbe935a` | NO-OP | Changelog |
| `790a7e2` | **ADAPTED (B)** | Inline-safe `PinnedLimit` with legacy fallback; constructor hooks only on the legacy path with the option on |
| `bea484a` | NO-OP | Changelog |
| `1abe476` | **ADAPTED (B, partial)** | Update only rows not yet extended; Community already writes on a background executor with `busy_timeout` and retries. Running through WhatsApp's own connection is deferred |
| `3b50e84` | NO-OP | Changelog |
| `cc51ac7` | DEFER | Version declarations 2.26.41: no DEX available; not declared |
| `5db954a` | DEFER | Community has no sticker coloured-outline hook |
| `1ef4e19` | REJECT | SettingsProvider/system scope |
| `8db37f4` | DEFER | Per-contact privacy keyed by LID needs a non-destructive key migration across 16 `CustomPrivacy.getJSON` call sites, the WhatsApp-side editor and backups; LID-only contacts currently fall back to global settings. Needs a design decision |
| `e5c347e` | DEFER | New feature (preload statuses) |
| `42f1632` | REJECT | Release automation (manual-only Actions) |
| `2f7ea49` | REJECT | R8/resource shrinking |
| `570d0e9` | REJECT | YukiHookAPI |
| `3d01fad` | DEFER | New feature (native scheduling, View Once text) |
| `01a8bb7` | **ADAPTED (B)** | `RevokeResultPolicy`; `loadNoneIncomingAckClass` |
| `2fc180b` | DEFER | New privacy option (unknown-number delivery receipts); product decision |
| `2d2301a` | **ADAPTED (B)** | Outgoing-call, newsletter-header and locked-chats resolver fallbacks |
| `f2551e7` | **ADAPTED (B)** | Abstract/interface filter; per-getter hook isolation in `SharedPreferencesWrapper` |
| `f4a8da1` | ALREADY | Community builds with JDK 21 |
| `0e4f586` | NO-OP | Changelog |
| `090d65a` | DEFER | Community has its own Deleted-for-Me store/vault |
| `cdff7a9` | DEFER | Contact-picker LID support: different Community picker; needs DB schema evidence (`wa_address_book`) |
| `8ec2216` | REJECT | Hide My Applist |
| `015d446` | **ADAPTED (C)** | `ReceiptPolicy.rewritableDispatch`/`suppressReadJob`; live ghost-mode read not adopted (Community reads `ghostmode_actual` preference) |
| `2a55b5b` | DEFER | Single props resolver: valuable, but a new signature-only resolver must be checked on DEX first |
| `4909f00` | REJECT | libxposed API101/Yuki migration |
| `84c9c81` | DEFER | Approved floating bar |
| `107373a` | NO-OP | Changelog |
| `166c62b` | DEFER | Community emoji-font rendering path is separate |
| `e6f8389` | REJECT | Preferences via SettingsProvider |
| `9b26a67` | DEFER | Approved floating bar |
| `38ecb66` | DEFER | HideSeen refactor without a distinct defect beyond `015d446` |
| `c4813e1` | DEFER | CustomThemeV2 drawable lookup; no Community failure observed |
| `ed39fc4` | DEFER | Community `filter_items`/FilterGroups differ |
| `1da24d7` | NO-OP | Changelog |
| `5d63769` | DEFER | Superseded version entries |
| `5d0398f` | REJECT | Preference transport via system provider |
| `6fa60ac` | DEFER | Community `FilterGroups` resolves filters differently |
| `94975f2` | DEFER | MediaQuality transcoder simplification; needs DEX check |
| `eda42f9` | DEFER | Profile photo argument handling; no Community failure observed |
| `02e4fc5` | **ADAPTED (B)** | Ghost-mode resolver: int in any position |
| `fa48d81` | DEFER | New preference (document scanner) |
| `ae736f3` | DEFER | Community does not declare the ML Kit components |
| `91088e3` | DEFER | Module UI message |
| `91ba9fc` | NO-OP | Gradle property |
| `656349e` | NO-OP | Changelog |
| `f4cc903` | DEFER | Contact-picker groups |
| `73d1c4e` | DEFER | As `ae736f3` |
| `8b71532` | ALREADY | Community shows no toast on bridge connection errors |
| `10d49b7` | DEFER | Different FeatureLoader ordering |

Earlier [`UPSTREAM_AUDIT_2026-10-06.md`](UPSTREAM_AUDIT_2026-10-06.md) findings were not replayed: its ported items are already on master and none of this pass's changes revert them.

### WaEnhancerX summary

Of 639 non-merge commits: 1 adapted, 1 adapted in part, 12 already present, 16 equivalent by a different Community design, 2 superseded, 273 deferred, 223 rejected and 111 no-op (see the ledger for every SHA). The source-reviewed X items most relevant to the requested priorities:

- DexKit/cache: `76d85e1`, `45bb199` adapted in A; `21f7aee` already covered by DexKit `returnType` matchers plus A's typed validation; `e18f202` already covered by namespace-agnostic class lookup.
- Startup/lifecycle: `c0efbfb`, `29052b7`, `127a430`, `81a46f1` already present; `7b2a4cb`, `9cffa1f` deferred.
- Receipts: the `85b0726`/`5327c6b`/`82cb076`/`48272b1`/`9f3616b`/`ac709e0` cluster has an equivalent contract in `ReceiptRelease` (per-message authorization with rollback, recipient from the verified job field). Status-receipt routing (`9dee603`, `13a28ce`, `886f066`) is deferred pending a Galaxy status-reply trace.
- IPC/preferences: `48cfde3`, `1ec2f17`, `106904c` already present; `e7774c1` deferred.

### Validation for this pass

See `handoff.md` ("Upstream porting pass — 2026-11-01") for the commands and results. Static/JVM validation does not establish that any resolver matches the installed WhatsApp build.

### Pending Galaxy S25 Ultra validation

1. Startup with a warm and a cold cache (after module update): no new `re-resolving stale descriptor` loops; feature error list unchanged or smaller.
2. Forward selection above the old limit, including groups and multiple channels (`ShareLimit`).
3. Pin more than three chats with the option on; confirm pins survive a sync and no premium icon; with the option off, native behavior.
4. Status and message revoke while Anti-Revoke is on, on the installed build (no `ClassCastException` in the WhatsApp log).
5. Hide Delivered alone, Hide Read alone and Ghost Mode: open chats with unread messages, then confirm WhatsApp keeps sending other jobs (no stuck JobManager) and that the reply/manual blue tick still releases receipts.
6. Locked chats with a LID-only contact: the contact list hides only locked contacts.
7. Typing privacy, outgoing-call privacy and locked-chats resolver logs: confirm which route matched.
