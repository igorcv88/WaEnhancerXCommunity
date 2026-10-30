# Agent handoff

This is shared working state for Codex, Claude, GPT, Antigravity, and subsequent agents. Read `AGENTS.md` and `ARCHITECTURE.md` first. Update this document after each task; preserve decisions and evidence instead of treating earlier plans as completed work.

## Current follow-up — zero static glass fallback, persistent OneMate, localized rim and composer icon contrast, 2026-10-30

Same branch and review-ready [PR #102](https://github.com/igorcv88/WaEnhancerXCommunity/pull/102). Physical screenshots 86068/86070/86072 show (1) a flat slate quoted-reply card after dragging out of a conversation, (2) grey attachment and camera glyphs over the blue glass composer while the microphone reads dark on white, and (3) the locally illuminated glass outline of `igorcv88/OneUILiquidGlass` Quick Settings. The user rejects every static fallback, including Battery Saver, and wants soft refraction plus an adjustable, moving-content-responsive rim on the same live glass layer.

Source audit: `GlassMaterialDrawable.draw()` and `GlassPane.onDraw()` still explicitly rendered `GlassRenderer.background(spec.neutralFallback(...))` on any missing recording; `GlassSurface.paint()` reverted to a layered GradientDrawable when a Liquid lens was pending/refused. This was a real source-level cause for the reported flat flash. Moreover, the OneMate default was **not** using the corrected temporal path: both the pane and drawable admission/scheduling were gated on `optics.corrected && optics.temporal`, so OneMate could reallocate its nonsticky budget on every scroll. The earlier statement that fallback was fully removed/persistence guaranteed was unjustified.

Corrections: `GlassMaterialDrawable` and `GlassPane` draw no substitute pixels when live is unavailable and log `PRESENTING_TRANSPARENT`; they never cache another chat's pixels across activity/source/geometry epochs. `GlassSurface` suppresses its layered glass background and unmasked BlurView while an eligible Liquid lens is missing; non-Liquid variants keep their existing material. Default OneMate now enables damage-driven capture plus the persistent shared `LiveBudget` even when experimental classic temporal settings are off; unchanged material objects are reused. Existing `BehindRecorder`/`GlassPaneGraph` cycle protection, capped retries and release-on-suspend remain intact. A temporary transparent interval is still possible if no safe source or effect exists; zero flicker cannot be proven without a device trace.

Optics: inspected `OneUILiquidGlass/main`'s `LiquidGlassShader.java` and `sfhook/refract.h` through GitHub. Its layer division and Quick Settings profile 3 use a base contour plus localized backdrop-luma edge light and slab-edge refraction; WhatsApp has actual same-process source pixels, so adapted the optical goal in the existing `KyantLens` AGSL rather than copying a SystemUI/SurfaceFlinger implementation. The one-pass shader adds localized white-content glint, retains the nonzero all-angle grazing contour and light/white contrast polarity, and reduces surface displacement to 65% for a less magnifying lens; blur/body wash are unchanged. Public float `liquid_glass_rim_intensity` (0–2, default 1) is exposed as a 0–200% slider and published on resume to both app-chrome and bar `KyantLens` paths; animated selected-tab bubble is unchanged.

Composer: scoped ImageView tinting to semantic attach/camera resource names under the footer, following the microphone's actual tint if available, otherwise the glass material's content colour. Original tint lists are restored when the composer is disabled. Absent IDs are logged as a bounded routing diagnostic. No broad recolouring of arbitrary images or message content.

Validation: JDK 21 parsed 12 touched Java files (including the new rim tests), pure settings/preferences compilation and bubble/header/palette smoke passed, and `git diff --check` passed; no Android SDK/compileSdk 36 in the container, so no APK, AGSL GPU compile, LSPosed hook proof, runtime performance measurements or device transition acceptance. No Actions workflow dispatched. Next device run must verify `bubble-factory`, `bubble-attached`, `PRESENTING_LIVE`/`PRESENTING_TRANSPARENT`, `GlassTrace` frame counters, attach/camera routing, and swiping/returning on low battery with messages/photos behind composer/header/replies. If a dark rectangle remains while trace says live, inspect WhatsApp's own quoted-card child backgrounds, not a supposed `GlassMaterialDrawable` fallback. Do not merge until tested.

## Current task — independent contact header and direction-aware bubble glass, 2026-10-30

Branch `feat/glass-bubble-scope-and-header-split-20261030` from `master@be2fed8` (PR #101 merged), review-ready [PR #102](https://github.com/igorcv88/WaEnhancerXCommunity/pull/102) created at `9eedd288`. User reports the existing global bubble-glass switch has **never worked** on their WhatsApp, though the message input glass is approved. Screenshot 86064 is the look reference: dark translucent received bubbles and blue sent bubbles. The separate quoted-reply glass control already exists and must remain independent.

Implementation in progress:
- Separate `liquid_glass_conversation_header` (floating contact-name header with the existing behind-header message scrolling) from `liquid_glass_toolbars` (home/other-page header). Existing header-on installations inherit the old value until saved independently. Generic toolbar binding does not cross into the conversation holder.
- `liquid_glass_bubble_scope` is `off` / `outgoing` / `incoming` / `both`, mapped to the legacy bubble boolean. The Liquid Glass settings page exposes the four choices and links the material conceptually to Change Bubble Colors in Customization.
- Bubble factory uses the cached resolver already used by `BubbleColors`, instead of a stricter unique-match requirement that could disable the previous glass hook. It keeps the direction parameter (3 is right/outgoing), preserves native outline through a drawable mask and can find the factory wrapper in a direct or nested native drawable. `GlassMaterialDrawable.attachOwner` allows the same `BehindRecorder` live effect after native attachment.
- Explicit custom left/right RGB is mixed into the existing app-surface OneMate tint only for that bubble; no chosen colour retains neutral material. Bubble-contained TextViews receive a black/white text colour chosen from the bubble tint, with native ColorStateLists restored when removed/recycled. The composer, bottom bar, quotes and other surfaces are unchanged.

Static evidence supports two possible previous failure routes: ambiguous DexKit string matches prevented hook registration, and view discovery only recognized a factory wrapper used as a *direct* view background; nested drawables had no owner and drew natively. These are hypotheses, **not confirmed causes** without logs from the installed build. Runtime source must record `bubble-factory`, `bubble-attached-*` and live/fallback presentation diagnostics. Wallpaper-dependent contrast and rich-text span colours cannot be guaranteed by the static palette alone. No physical capture, performance measurement or WhatsApp version confirmation is available yet.

Validation during this task: 11 changed Java sources passed a JDK 21 parser-only check; standalone JDK 21 compilation and executable smoke checks passed for `GlassSpec`, `SemanticTheme`, `BottomBarPreferenceSchema`, `LiquidGlassSettings`, `PreferenceSchema` and fake preferences (legacy migrations, direction selection, tint choice, text colour and fallback). `git diff --check` passed. A full Gradle `:app:testWhatsappDebugUnitTest` attempt downloaded Gradle 8.14.5, first failed on Java 21 absent, then passed Java 21 toolchain configuration with a temporary JDK but stopped at **missing Android SDK (compileSdk 36)**. No APK or full unit suite was built; no CI dispatched. Tinted specs are cached by the resolved base material and colour to avoid per-frame fallback reallocation.

Next: build/install PR #102 on the S25 Ultra and test both directions, tinted and default, light/dark, recycled rows, media/quoted messages, scrolled wallpaper, text contrast and contact-header independence. If the factory marker never appears, collect targeted DexKit/WhatsApp resolver logs before widening hooks. Do not merge before runtime validation or dispatch a GitHub Actions workflow without an explicit request.

## Current task — separate navbar, bubble and app-chrome calibration, 2026-10-22

Branch `fix/app-glass-blur-and-rim-20261022`, based on `master@bf513f99`. Screenshot 80850 shows recognizable sticker structure behind the composer and a weak boundary. User requests stronger app-surface blur. Follow-up 6.28s video shows the animated bubble's contour much stronger than the bar's, superseding the earlier request to leave the navbar entirely unchanged. Filmstrip reviewed at 2fps. Do not attribute the difference to placebo or claim the wrong view was targeted: source shows recent surface changes reached `LiquidLens.applyOneMate` for the bar and `LiveBackdrop` for app chrome, while `LiquidBubble` used its separate original recipe. Actual runtime compilation/install is not proven by source inspection.

Implemented three treatments:
- Bar/GlassSurface view route: same 10dp blur and neutral body, contour gain 1.6, width 1.15dp (scroll button shares this route).
- App chrome through LiveBackdrop (composer/header panes and drawable surfaces): 30dp blur, contour gain 1.8, width 1.25dp. Capture and LiveBudget both use appPadding. Same tint/saturation/refraction.
- Animated bubble: existing highlight reduced from glass progress to 0.45 times glass progress. Geometry, refraction/chroma, shadow, selected-tab wash and motion unchanged.

Gain participates in Params.key and is uploaded explicitly. No additional rendering pass or capture source, budget capacity, setting or animation change. Larger app blur carries larger padded work and can cost more GPU time. Large low-frequency colour fields can remain visible even at 30dp.

Validation: six source-extracted numerical shader tests pass, including stronger app contour and unchanged interior. Java parameter tests updated for independent app/bar blur/padding and reduced bubble highlight; not run because the Android toolchain remains absent. A numerical comparison of shader default gain=1 against the old shader was exact for 1000 cases; this proves the uniform alone is neutral, not that the final bar profile remains unchanged (its gain is intentionally 1.6). Diff whitespace check passes. No APK, workflow dispatch, physical aesthetic acceptance or performance measurement.

Next: build focused PR; compare bar at rest and bubble expanded in the same recording, verify less dominant bubble and more persistent full bar outline. Compare input over moving stickers/media with keyboard open/closed and in both themes. Review compilation and frame cost before claiming final acceptance.

## Previous task — PR #99/#100 review follow-up, 2026-10-22

Both PRs are merged in `master@6eacdc0a`. User explicitly authorizes corrections directly on the default branch. PR #99's completed review has no findings. PR #100 has one valid P2 (`discussion_r4353661876`): over a white light-theme body, the reflected target and body are both white, so max-only brightening cannot delimit the surface.

Correction: retain the existing dark-background arithmetic exactly, but gradually switch the narrow contour target toward 55% of the body RGB once composited body luminance rises from 0.65 to 0.95. The polarity transition is smooth. The contour's share of the combined reflection limits darkening to the narrow grazing band; the wider directional bevel remains luminous. Body colour/opacity, blur, refraction, geometry, capture, classic/fallback and bubble are unchanged. No new input uniforms or samples.

Validation: five executable Python tests evaluate the actual extracted production AGSL arithmetic (not a hand-copied model): visible rim on white/near-white, illuminated rim on dark backdrops, unchanged interior, continuous bright transition, and 1000 randomized premultiplied-alpha bounds. They use NumPy and the existing shader extractor. Java/AGSL compilation and APK remain unverified because Android/Gradle/compiler tooling is absent. Physical aesthetics in both themes still require confirmation; no build dispatch.

## Previous task — persistent integrated rim after physical review, 2026-10-22

Branch `fix/persistent-glass-rim-20261022`, based on `master@8a82ad72` after PR #99 merged. User physically approves the blur and body colour but reports the outline disappeared, especially around the composer. Preserve all body, blur and capture parameters.

Cause in #99: the envelope is zero exactly on the outline, directional light can be zero along whole runs, and dark backdrop response plus a mostly dark reflected colour further suppress the remaining light. New treatment joins a narrow Gaussian grazing contour (sigma 0.65dp, minimum 0.75px) to the existing 4dp bevel, using one reflected colour. The contour coefficient has an all-angle 0.085 minimum over black, rising with local pre-wash backdrop luminance; the directional bevel is reduced to 45% of its previous coefficient to avoid a second strong band. Reflection target is neutral white with 15% live backdrop colour. No inset stroke, extra draw, extra sample, timer, body tint/saturation/blur/refraction/capture change. Selected-tab bubble and classic/fallback are untouched.

Reference inspected: `igorcv88/OneUILiquidGlass@2b278cd4cb7dda8e9562d360033fbdbb9320de89`, `LiquidGlassShader.java` COMMON/edge and `sfhook/refract.h`. Heads-up/profile 0 gets persistent module-side contour plus directional lit runs; its compositor shader applies refraction/filtering, not rim lighting. Its module overlay explicitly cannot sample compositor backdrop pixels: do not claim its lighting coefficient dynamically samples that backdrop. Adapted the design principle, not its shader code or cross-process hooks. Excluded geometric RGB fringe, inset hairline geometry, body filtering and lockscreen cool tint to preserve the approved neutral WaE material.

Validation: diff whitespace and numerical contour checks pass (all angles over black retain light, brighter local backgrounds increase contour gain, falloff is continuous and negligible in the body; premultiplied bounds preserved). These are not Java/AGSL compilation. Existing environment still lacks cached Gradle/Android SDK/compiler and glslangValidator; APK and physical acceptance remain pending. No workflow dispatch or merge.

Next: build the focused PR and compare input/navbar over black, white and moving coloured content. Confirm a visible complete contour, smooth corners, no detached/double line, unchanged body/blur and readable input icons. The contour calibration needs device confirmation.

## Previous task — neutral Liquid Glass surfaces, 2026-10-22

Branch `fix/neutral-liquid-glass-20261022`, based on `master@b85d2f5`. Focused PR targeting master; no merge or workflow dispatch.

User screenshots show a blue-grey cast and continuous bright outline on the floating bar and especially the composer. User explicitly approves the existing blur and requests preserving it. The default OneMate path uses `KyantLens.surface` from both `LiveBackdrop` and `LiquidLens`; its old recipe boosts saturation to 1.5 and overlays the theme-derived RGB at 40% default opacity. This is a code-supported explanation, not device proof of the active renderer/settings.

Changes: retain the exact 10dp blur, radius conversion, padding, refraction, capture and recovery paths. Use original backdrop saturation and an achromatic black/white readability wash, 18% at recommended opacity (slider remains active). Remove the body surface's constant stroke; apply a lower-strength directional reflection with a smooth 4dp envelope that fades at the outline and interior. Clamp light-facing normals before raising them to the fourth power, rather than illuminating both sides with a squared signed dot product. Reflection colour and strength follow the live refracted backdrop before the neutral wash, so scrolling content modulates the bevel without a timer or added capture. This incorporates the user clarification: retain a gently illuminated, integrated, dynamic edge, not an absent edge. Preserve the selected-tab bubble's existing material and motion. Classic renderer and fallback remain unchanged. Update material caption and architecture. Existing KyantLens input tests updated and a neutral-wash regression case added.

Validation: `git diff --check` passes. Full Gradle/JUnit execution blocked: Gradle 8.14.5 is not cached and its download fails with network unreachable; Android SDK/JDK compiler unavailable. Shader checker cannot run because glslangValidator is absent. Numerical envelope/neutral transmission checks run separately in Python; these do not compile Java or AGSL and are not device evidence. No APK built or installed.

Next: build this PR, then compare the same composer and home views with current settings, both themes and a bright image behind the input. Confirm readable hint/icons, no blue pigment added over neutral content, softer edge and unchanged blur. Actual aesthetic acceptance remains pending. The source background itself can be blue/green; neutral glass preserves those colours rather than removing them. The 18%/0.32 reflection calibration is provisional until physical review.

## Previous task — WhatsApp emoji rendering follow-up, 2026-10-22

The original emoji picker PR #97 **was merged** into `master` at `92242056f46f3e8ca33bb9b15904c239f98d6e36` at 18:56 UTC. A subsequent span-bypass commit was mistakenly pushed to the already-merged feature branch; it was **not included in master**. Preserve PR #97 as historical. Follow-up must be based on `master` in a new PR (`fix/emoji-span-rendering-lite-20261022`) and must address the outstanding Codex P2 review comment about Lite Mode as well as the physical rendering failure.

The follow-up adds a narrow, temporary `ProviderClient` solely for `openEmojiFont()` when the global bridge is unavailable (notably Lite Mode, which deliberately skips `WppCore.initBridge`). This keeps the font read asynchronous and does not initialize the global bridge or enable unrelated capabilities. Its use is logged as `using independent font bridge`. The independent bridge path and the span interception require an APK build and physical verification.

Review follow-up for PR #98 (P2: spans inserted while the font is still loading): the `setSpan()` hook now acts **after** the original insertion. When `Typeface` is ready, it installs a text-only `TypefaceSpan` before removing the emoji-only WhatsApp bitmap span. When loading has not finished, it keeps the bitmap span temporarily and records weak references to the text and original span (bounded to 256). Successful font loading posts a main-thread pass that checks whether each original span is still attached to an emoji-only range before replacing it; loading failure leaves the original spans untouched. The hook also schedules a flush if loading completes during insertion. Distinct `First emoji replacement span deferred`, `First emoji replacement span bypassed`, and `deferredEmojiSpansApplied=N` messages separate queueing from actual replacement. The old P2 was valid; this change still needs a physical build/test and cannot guarantee that WhatsApp copies no spans into other buffers before the load completes.

## Original task — import user-selected emoji TTF for WhatsApp, 2026-10-22

Branch `feat/user-selected-whatsapp-emoji-font`, based on `master@299fd5c93faef1d1236f6d20cd1c9d0a579c4682`. PR [#97](https://github.com/igorcv88/WaEnhancerXCommunity/pull/97) was merged. The initial import feature reached `master`; the later span-bypass experiment did not. No workflow dispatch or phone installation was performed by the assistant.

Background: One UI 9 FlipFont/ScamsungFonts can display Apple-style glyphs in most fields; WhatsApp still displays its own emoji shapes before sending. In the previous WaEnhancer implementation, `disable_defEmojis()` intercepted a broad set of `ReplacementSpan`/`ImageSpan` draw methods and called `canvas.drawText` with the original WhatsApp `Paint`. The enabled toggle did not prove the hook installed or that a custom typeface had been selected.

Changes in this branch:
- A companion-owned color-emoji TTF picker in Conversation settings, also reachable from the embedded settings interfaces. Import uses Android's document picker, bounded 128 MB copying, SFNT directory checks, Android `Typeface` validation, and atomic replacement in a private fixed path. Choose/remove are explicit actions; imported artwork is never committed or bundled.
- A dedicated, UID-authenticated, read-only `openEmojiFont()` Binder method with no path argument. Existing generic file access remains restricted to shared-storage media/theme paths.
- The emoji span hook starts a one-time asynchronous typeface load, clones `Paint` for emoji-only spans, includes inherited Android `DynamicDrawableSpan.draw`, skips non-emoji spans and fails closed when a resolver or draw fails. One-time diagnostics log install count, font availability and first intercepted draw without message content.
- Preference XML, raw embedded settings map, activity manifest, strings, schema metadata, architecture and JUnit tests updated.

Physical failure and follow-up: User installed the new APK and reported that Apple emojis appeared briefly when a conversation opened, then reverted to WhatsApp emojis after editing text. An LSPosed export at 2026-10-22 16:21 contains three WA process launches at 16:16, 16:18 and 16:19, each reporting `[WAEX][EmojiFont] fontLoaded=true`, `installedDrawHooks=16 spanClasses=18` and **no** `First emoji draw intercepted` marker. The Binder and font load are therefore working; the active WhatsApp render path is not covered by the observed `draw()` hooks. Added a second, guarded intercept on framework `SpannableStringBuilder`/`SpannableString` `setSpan`, suppressing emoji-only `ReplacementSpan`s and applying `TypefaceSpan` for the imported font. It does not touch regular text, other spans, system_server, or GMS. Bounded diagnostic entries show replacement span classes and lengths but never contents. This is a hypothesis-driven fix requiring another phone test; no success claim yet.

Local validation: 8 pure-Java JUnit tests pass on Temurin JDK 21, including optional validation against the user's 42 MB `iOS18.4.ttf`; five changed XML documents parse; embedded settings JSON parses; `git diff --check` passes. Full Android Gradle suite/APK build has **not** passed here: Gradle 8.14.5 is available but this container lacks the Android SDK and initially had only JRE 17. Do not present these tests as an APK/physical success.

Physical acceptance steps (after an Android build): install APK, select `iOS18.4.ttf` from the module app's Conversation settings, leave **Disable Default Emojis** on, force-stop/reopen WhatsApp, compare `😍 🫩 ❤️ 🇧🇷 🧑🏽‍⚕️` before sending, in outgoing/incoming bubbles, captions, and reactions. Inspect LSPosed log entries prefixed `[WAEX][EmojiFont]`: `fontLoaded=true`, `installedSpanHooks=2`, `replacementSpanCandidate=... emojiOnly=...`, and ideally `First emoji replacement span bypassed`; if spans were created before font loading, also expect `First emoji replacement span deferred` and `deferredEmojiSpansApplied=N`. The bypass marker proves span substitution happened, not that every WhatsApp emoji surface looks correct. If candidates remain absent, collect a targeted render-path diagnosis rather than adding broad hooks. Verify non-emoji spans, composer typography, app restart and WhatsApp Business separately. No Google Play Services or system_server hooks, font systemless mount, or external root module is added.

## Previous task — constrain bridge capabilities and restore Knox Matrix parity, 2026-10-22

Branch `review/app-only-no-system-server-20261022`, PR [#96](https://github.com/igorcv88/WaEnhancerXCommunity/pull/96), security follow-up based on head `887b659db1a82c56361be14b1625765ca3b1a8ee`. No merge/public release/phone installation authorized. See [the app-only audit and follow-up](docs/APP_ONLY_REVIEW_2026-10-22.md).

Fixed the UID-only file bridge: canonical primary shared-storage boundary, seven existing media-output categories and extension allowlists, read-only theme/selected-wallpaper images, write-only media outputs, O_NOFOLLOW and opened-descriptor validation before truncate/return. No listFiles capability; no production caller needs it. Configured download/wallpaper paths cannot authorize private module storage. HookProvider writes/removes only schema-known public non-secret keys and validates both declared/raw types. Private controls reuse the companion editor rather than silently editing a host-only mirror. Removed the three spoof controls from the raw injected settings JSON as well as prior XML/search changes.

Failed initial preference hydration and observer registration now retry on later reads with a monotonic 500 ms limit. Notifications preserve invalidation across the throttle; cached values survive failure and an authoritative snapshot restores synchronization. Startup-only feature hooks can still require restarting WhatsApp after an initial configuration outage; this is a physical test item, not an automatic reinstallation claim.

Validation: four local methods passed, including executable production entrypoint, auth, Binder/provider, schema/policies and deferred/provider preference classes against Android stubs. Tests exercise all 26 private keys, valid/malformed public types, private paths/DBs/traversal/symlinks, descriptor modes/rejection before truncation, cold-start retry, settings update and companion loss/recovery. Canonical files/symlinks use real host temporary files; Binder/Os/provider rules are simulated. Full Android build, existing JVM suite, compiled APK gates and physical API-37 operation remain pending. User explicitly prefers no browser; the exposed GitHub connector has no new-run dispatch operation. Provide the final PR-head expected_sha for a manual `mode=lab`, `channel=beta` execution. The previous `887b659...` build input is obsolete after these changes.

Preserved app-only scope/entrypoint, public/private storage and migrations, safe named transcription/Tasker secret operations, media/theme functionality within the supported shared-storage locations, and all existing phone modules/settings. No browser, workflow dispatch, phone change, merge or release performed during this follow-up.

## Previous task — hidden FAB reappearing, stronger blur, 2026-10-15 (ninth recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@056cb7c` (PR #94 merged). New PR from this branch.

User report, ninth recording (704×322, 3.85 s):
1. **The call FAB ("Start a call") showed behind the bar while swiping pages**, although the bar's FAB mode is Hidden. It did not appear after tapping a tab.
2. **The glass looked low quality, not like Liquid Glass.** The blur reads as weak; the FAB behind the bar looked pixelated.

Findings:
- **FAB.** Hidden mode was enforced only on:
  - a FAB's own constructor-attach and layout (only for the class names in `FAB_CLASS_CANDIDATES`);
  - the install sweep, 500 ms after install;
  - list-scroll events (`animateFabs`).

  A FAB that WhatsApp shows during a page swipe was hit by none of these.
- **Blur.** The OneMate capture is already 1:1 (no downsampling). The impression comes from miuix's 4dp blur (sigma 1.8dp, ~5 px): the FAB's edge stays hard inside the bar, and the 24dp rim lens drags that hard edge into streaks.

Implemented:
- `FloatingBottomBar.keepFabsHidden(root)`: in Hidden mode, a global-layout listener on the home root sweeps `findFabs` and sets any non-GONE FAB to GONE after every layout pass. Showing a GONE view always requests a layout, and the listener runs before that frame's draw, so the button is never drawn.
- `KyantLens.BLUR_DP` raised from 4 to 10 dp (sigma 4.5dp). The edge mode is CLAMP, so the bar's edges are not darkened. The capture padding grows with it, through `KyantLens.padding` and `LiveBudget`.
- ARCHITECTURE.md and the Liquid Glass page caption are updated.

Validation:
- 463 tests, 0 failures.
- AGSL check passes for all three shaders.
- Debug APK SHA-256 `a142873b0a3abe83b565ffd54daccd411190973b0aa4d65dbe66aca105dc6bac`.
- Not device-validated.

Unknowns:
- Whether the swipe-time FAB is under the same root as the bar (assumed: same activity content).
- Whether WhatsApp shows it in a way that skips a layout pass, for example a FAB kept VISIBLE and animated by alpha only. The mode sets GONE, so it should not.
- The frame cost of the larger blur on app surfaces.

Next:
- Record a swipe across all tabs with FAB mode Hidden.
- Compare the bar glass over the FAB or a colourful wallpaper.
- If 10dp is too soft or too strong, the next step is a user blur slider for the OneMate material.

## Previous task — tap as one continuous gesture, 2026-10-15 (eighth recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@6fb33f8` (PR #93 merged). New PR from this branch.

User verdict on #93: the tap now feels stuck ("travado"): it rises, then moves, then closes, like separate steps. The user proposed a different motion: the bubble starts expanding at the origin, keeps expanding toward the tapped tab, arrives large, then collapses, with the collapse a little slower. The swipe is judged right; no change was requested to it.

Measured in the eighth recording (888×270, 273 frames, about 54 fps), tap calls → groups:
- the rise started on Calls at 0.70 s;
- the bubble jumped to Groups with the page between 0.78 and 0.83 s;
- it then **sat fully inflated for ~300 ms** (0.83–1.12 s);
- it shrank by 1.25 s.

Cause: the pager-follow took the tap over, so the glide from #93 did not run as designed. `holdPagerFollow` cleared itself after the pager had been still for 80 ms, and WhatsApp starts its page scroll more than 80 ms after the finger lifts. The follow then lifted the bubble, snapped it with the page, and held it until the pager settled. The stepped design (rise, then travel, then land, each eased to a stop) would also have read as halting.

Implemented:
- **The tap is one gesture** (`BubbleMotion.applyGlide`):
  - travel 0–300 ms on `1-(1-x)^4(1+4x)`: starts from rest, peaks at a quarter of the way, eases in;
  - rise 0–180 ms, ease-out from the current height, so the bubble grows while travelling and arrives full size;
  - collapse 270–500 ms, ease-in-out, slower than the growth as the user suggested;
  - a redirect restarts from the current position and height.
- **The tap hold no longer ends on stillness.** `tapHoldUntil` lasts `EXPECT_MS` (700 ms) from the finger lifting. `pollPager` also never follows while a glide runs. `expect()` (drag commit) keeps its own settle-based hold.

Refraction review (frames at 0.78 s and 0.95 s):
- The lens reads correctly: content is magnified 1.39× and smeared at the rim, with a lit edge.
- The bubble's interior is clearly darker than the bar, because it shows the page without the bar's 40% surface tint, plus OneMate's 15% inner shadow and 3% black. In the dark theme it reads more like a dark hole than clear glass.
- Not changed: the user called the look right. Option offered: a faint light wash inside the bubble while risen.

Codex review on #94: a swipe started within 700 ms of a tap was not followed. Fixed: once the tap's glide starts, the fixed hold gives way to the settle-based hold, which ends as soon as the pager rests on a page.

Validation:
- 463 tests, 0 failures (new or changed: continuous-gesture timing, travel curve, wall-time glide).
- Debug APK SHA-256 `8efc7390c45f886585562fe81e489b537654f425cdc4e314653eb12842b64a30`.
- Not device-validated.

Next: record taps (adjacent and two tabs apart). Check:
- the bubble leaves the old tab at once, growing;
- it arrives full size around 270 ms;
- there is no full-size hold;
- the collapse is smooth and about 230 ms.

## Previous task — tap timing and landing icon, 2026-10-14 (seventh recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@03a7e4a` (PR #92 merged). New PR from this branch.

User verdict: the look and the transitions are right; only the speed of a tab tap is wrong ("explodes and compresses at once"). Their phone runs the animator duration scale at 0.33×.

Measured in the seventh recording (1080×262, 645 frames, about 60 fps):
- **Tap chats → groups:** the bubble appeared on Groups at once, peaked at about 80 ms and was back to the pill by about 200 ms. Groups → calls took about 270 ms. **There was no travel.** WhatsApp's click jumps the pager to the new page in about one frame, the bubble followed the pager there, then swelled and collapsed in place.
- **Page swipe groups → chats:** travel about 170 ms, hold about 100 ms, shrink about 200 ms, so about 480 ms visible. The user's reference for "right".
- **Landing defect:** for the last about 100 ms of each landing (taps at 0.90–0.98 s and 1.58–1.66 s, swipe at 4.43–4.53 s) the selected icon showed small and dim inside the pill, then popped to full size when the native indicator returned. Two causes:
  - the morph scaled the canvas, so content shrank to about 60%;
  - the pill copy was drawn over the icon, because the bubble's Z is above the tab row.
- **One frame with no indicator** at the start of a tap (0.699 s). Not specifically fixed; the glide starts on the origin tab, which should cover it. Device check pending.

References checked:
- Apple does not publish the iOS 26 tab bar's spring.
- SwiftUI's `.smooth`, `.snappy` and `.bouncy` default to a 0.5 s duration.
- Material 3 Expressive's default spatial spring has stiffness about 380 (third-party token mirrors), settling in about 0.3–0.35 s.
- The old tap springs (stiffness 1000 for value and press, deflate on arrival) landed in about 0.2 s.

Agreed with the user (AskUserQuestion): a tap takes **about 450 ms, fixed**, not scaled by the animator setting. Animator scale 0 ("remove animations") still snaps.

Implemented:
- `BubbleMotion` glide, a deterministic timeline used by `animateTo` when no finger holds the bubble:
  - rise 0–120 ms, ease-out cubic;
  - travel 120–300 ms, ease-in-out cubic, with analytic velocity so the squash still applies;
  - land 300–450 ms, ease-in-out.
  - The scale springs keep their wobble.
  - A redirect mid-glide continues from the current height and position.
  - `press`, `lift`, `follow` and `snapTo` cancel a glide.
- `LiquidBubble.holdPagerFollow()`:
  - `BubbleTouch` calls it on ACTION_UP over a non-selected tab, so the pager's jump is not followed and the selection report starts the glide.
  - The hold ends when the pager rests on a page, or after `EXPECT_MS` (700 ms). `expect()` shares it.
  - `setTabs` no longer snaps a glide that is already heading to the selected tab.
- `LiquidBubble.onDraw`:
  - the morph now sizes the shape, not its content, so icons are 1:1 at the hand-off;
  - `settleScale` eases the spring scale to exactly 1 over the last 30% of the deflation;
  - `drawIcons` redraws the tab row inside the pill at the pill copy's opacity, so the landing ends as WhatsApp draws its tab: icon over pill.

Codex review on #93, both fixed:
- the glide timeline advances on uncapped wall time; only the springs keep the 50 ms cap;
- the landing pill copy and the redrawn icons are composited in one layer that fades as a unit, as WhatsApp's pill-plus-icon would.

Validation:
- 462 tests, 0 failures (5 new: glide timing, redirect continuity, interruption, wall-time glide, `settleScale`).
- Debug APK SHA-256 `f12a3e14f1b67be59b9ef6f749aa56933b5bbbb6fc6c33efb830404c83f80b5c`.
- **Not device-validated.**

Visual change to confirm on the device: during the rise the content inside the bubble no longer zooms up from about 60%; it starts at 1:1 and magnifies to the pressed scale.

Next:
- Record taps (adjacent and two tabs apart), a swipe, and a drag. Check:
  - travel from the origin tab;
  - total tap time about 450 ms;
  - no small or dim icon on landing;
  - no empty frame at tap start.
- If a tap shows no travel, the pager jump is still being followed. Check that `BubbleTouch` sees ACTION_UP for non-selected tabs on that build.

## Previous task — bubble hand-off polish, 2026-10-14 (fifth recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@2fc2c30` (PR #91 merged). New PR from this branch.

User verdict: very close. The fifth recording (1080×234, 11.7 s) showed three defects:
- **Bubble shrank mid-swipe and re-grew** (at 2.0–2.9 s) when the finger paused between pages. Cause: "pager still for 80 ms" ended the follow. Fix: the follow ends only when the pager is still *and* within `PAGE_SETTLED_EPSILON` (0.01 page) of a whole page.
- **Double indicator during motion** (Codex P2 on #91): WhatsApp's pill stayed on the old tab, and the lens recorded it. Fix: the bar listener hides the native indicator views (`findNativeIndicators`: the plain background View beside each tab icon, as INVISIBLE) while press progress > `VISIBLE_PROGRESS`, and shows them when the bubble has shrunk back. The state is re-applied after layouts and restored when the host is removed.
- **"Strange green" flash on landing**: the morph wash was the theme accent at 45%, much lighter than WhatsApp's dark-green pill. Fix: the bubble draws a mutated copy of the native indicator's background drawable (`ConstantState.newDrawable().mutate()`), faded by (1−p)². At zero progress it is the native pill, so the hand-off has no colour jump. The accent wash remains a fallback when the drawable cannot be copied.

Validation: 457 tests, 0 failures. Debug APK SHA-256 `37db5c13e52a5c102755fb5bdb9c847fd7cfa8baee6f75891751cd4dc422e88d`. Not device-validated. Known approximation: the copied drawable is drawn in bubble space and canvas-scaled to the indicator's size, so an absolute corner radius scales with it (slightly less rounded than the native pill at the end of the morph). If visible, draw it at indicator bounds with an inverse scale. The first-swipe-after-start glitch from the previous report is still uninvestigated.

## Previous task — native indicator at rest, bubble morphs from it, 2026-10-14 (fourth report)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@e5a3c61` (PR #90 merged). New PR from this branch.

User report (screenshot plus description): transitions, drag and swipe-follow are now right ("perfect"). The resting state is still bad: a white/grey, opaque, vapour-like bubble. The user's decision: at rest, go back to WhatsApp's small green indicator around the icon, and keep the bubble only for motion. Their ideal: the green indicator grows into the glass bubble, travels, and shrinks back into the green on arrival.

Implemented, a first step of that ideal:
- `suppressNativeIndicator`/`restoreNativeIndicator` were removed. WhatsApp's Material active indicator and the item ripples stay as WhatsApp draws them.
- `LiquidBubble.onDraw` draws nothing below `VISIBLE_PROGRESS` (1% press). At rest only the native indicator shows, and the resting lens no longer redraws with the capture view.
- While rising, the bubble's size morphs from the native indicator's (64×32dp, `INDICATOR_W/H_DP`) to its own, with an ease-out (`morphScale`). An accent wash (45% × (1−p)²) fades out as the glass (lens and rim = press progress) takes over. On landing the same curve runs back. `REST_GLASS` was removed.

Validation: 457 tests, 0 failures (`morphScale` test added). Debug APK SHA-256 `4436ee2e33a63e58511dc28447c22e4b6658c4bf472c43733a2695ff767b9906`. Not device-validated.

Known issue the user reported, not fixed: right after opening WhatsApp, a slow finger swipe on the chat list sometimes snapped the bubble back to the origin tab, then it stabilised. Likely cause: the first frames before `pageToBubble`/`barPagers` are populated by the first `updateBubble`, or a selection report arriving mid-swipe while `following` was not yet set. Investigate with logging of `pagerPosition` and `setTabs` during the first swipe.

### Plan for the full "green becomes the bubble" animation (user's ideal, not implemented)

1. **Measure the real native indicator.** Find the active-indicator view per item (the plain View behind the icon, as the removed heuristic did) and read its bounds and corner radius in host coordinates each layout. Use those instead of the 64×32dp constants, so the bubble is born exactly on top of it.
2. **Hand-off without double drawing.** When progress leaves 0, hide the native indicator for the duration (alpha 0, restored on landing) so the green pill visibly becomes the bubble rather than staying behind. On landing, show the native indicator on the new tab only after the morph-back finishes, to avoid a flash of two pills.
3. **Colour morph.** Interpolate from the indicator's actual drawable colour (sampled from its background `ColorStateList`/`MaterialShapeDrawable` fill), not the theme accent, to the glass wash, in linear light.
4. **Shape morph.** Morph the corner radius and height from the indicator's to the bubble's, which the scale curve already approximates. Optionally add a short overshoot on the way out, using the existing scale springs.
5. **Acceptance:** recordings at 120 Hz of tap, swipe and drag, checked frame by frame for no double pill, no colour jump, and no size pop at the start or end.

## Previous task — bubble glass at rest, pager sync, green flash, 2026-10-14 (third recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@d3f4321` (PR #89 merged). New PR from this branch.

Third device recording (1080×262, 8.5 s):
- **Swipes never moved the bubble.** It stayed flat and jumped tab to tab after the page settled. The `setScrollState`/`dispatchOnPageScrolled` hooks on `androidx.viewpager.widget.ViewPager` never fired, so this WhatsApp build's tabs pager is not that class by name (obfuscated). Replaced by polling: `FloatingBottomBar.pagerPosition` reads the pager's scroll offset every window frame through the bubble's pre-draw listener, and every bubble frame while following. It uses `scrollX/width` for a classic pager, or reflective `View.computeHorizontalScrollOffset` on a single horizontally-scrollable child for a RecyclerView-backed one, then maps pages to bubble indices through `getTabIdForIndex`. Movement lifts and follows; two still frames end the follow and deflate the bubble. After a drag commits a tab, the pager's own scroll is ignored until it settles. The dead method hooks were removed.
- **Green flash on page change.** During a selection's brief rise the lens drew the tab row through an accent `SRC_IN` filter, turning every icon green. The row is now recorded in its own colours.
- **Not glass at rest.** The bubble keeps `REST_GLASS` = 35% of the pressed lens at rest (refraction and lit rim, but no inner shadow and no rise). `KyantLens.bubble(dark, press, glass, density)` decouples the wash and shadow (press) from the lens and rim (glass). To keep the resting lens current, it redraws in the same frame whenever the bar's capture view is dirty (`View.isDirty`), so it never starts a frame loop of its own. The hairline edge is now drawn only on the non-lens fallback path.

Validation: 456 tests, 0 failures. AGSL check passes. Debug APK SHA-256 `e8a3542b4416e945eea4cf32823c0efc96a54db8cc64e37f7541e9eafe905b64`. Not device-validated. Unknowns: whether the pager's scroll source is detected correctly (scrollX or the inner RecyclerView); the per-frame cost of the resting lens while the list scrolls.

## Previous task — bubble rest look and swipe glass, 2026-10-14 (second recording)

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@4fea4e5` (PR #88 merged). New PR from this branch.

Second device recording (1080×274, 6.7 s): dragging the bubble with a finger is now stable. Two problems remained:
- **Rest look.** It was a solid accent pill as wide as the tab (the 22% accent wash). The user asked for OneMate's look: a translucent bubble slightly larger than a plain pill, or failing that, the old small indicator. Since WhatsApp's own pill is now hidden, the rest wash is back to OneMate's 10% white (dark) / 10% black (light), plus a hairline edge (16%) that fades out as the lens takes over.
- **Swipe moved flat, with no glass.** `LiquidBubble.follow` now lifts the bubble into the lens for the length of the swipe (`BubbleMotion.lift`, a press without a finger). `endFollow` deflates it onto the tab when the pager goes IDLE.

Validation: 455 tests, 0 failures (bubble rest test reverted to the 10% wash; lift/release test added). Debug APK SHA-256 `0070a5cb17fbe2eefb1cf0de1df3f1312b5e4b94df3cbe4f4eceb7691c22ffce`. Not device-validated.

Next: re-record rest, swipe and tap. If the translucent bubble still reads badly, the user's stated fallback is a small indicator around the icon only.

## Previous task — bubble device fixes, 2026-10-14

Branch `claude/liquid-glass-nav-bar-ktxdge`, restarted from `master@202b58d` (PR #85 merged). New PR opened from this branch. No workflow dispatched.

Device evidence: a screen recording of the bar from the user (1080×280, 10 s, 120 fps). Four defects were found in it, each traced to a cause in the code:
1. **Grey "white bubble" at rest.** WhatsApp's Material active-indicator pill (dark green) was still drawn, and OneMate's 10% white rest wash sat around it as a second pill. Fix: `FloatingBottomBar.suppressNativeIndicator` calls `setItemActiveIndicatorEnabled(false)` by reflection; if that fails, it hides the plain background View beside each tab icon. It also removes the items' press ripples. Everything is restored in `removeGlassHost`. The rest wash is now the accent at 22% (`KyantLens.BUBBLE_REST_ALPHA`), so the bubble is the only indicator.
2. **Two icons inside the bubble** (one white, one green, moving apart). The tab row's own elevation sorted it above the bubble, so the real icons were drawn over the bubble's magnified copy. Fix: the bubble's translationZ is kept 1px above the tab row's Z, re-asserted on every layout. It has no outline, so it casts no shadow.
3. **Solid green slab in the lens.** The accent `SRC_IN` tint of the recorded tab row also filled the native pill and the ripple. Fixed by (1).
4. **Bubble lags the page swipe.** It moved only after `dispatchOnPageSelected` and the page settling. Fix: hooks on androidx `ViewPager.setScrollState` and `dispatchOnPageScrolled`. While a user swipe is in progress (DRAGGING through IDLE), the bubble follows the fractional page flat (`LiquidBubble.follow` / `BubbleMotion.follow`, no rise). The page-to-tab mapping goes through the existing `getTabIdForIndex` and view ids. Tab taps keep the rise→travel→land animation. A swipe ending within 0.05 tab of the selected tab is snapped, not re-animated.

Validation: `:app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug` — 454 tests, 62 suites, 0 failures (the bubble tint test was updated; a page-follow test was added). AGSL check passes. Debug APK SHA-256 `caabf1c90e9d70634e5cc221e083d59a4644ade347f05417d2db3b892de5d88d`. Not device-validated. Unknowns: whether the reflection method exists in the user's WhatsApp (if not, the per-item heuristic must find the indicator view); whether the tabs pager is androidx `ViewPager` or a subclass that overrides those methods; whether removing item ripples loses any WhatsApp press feedback the user wants.

Next: re-record the same gestures (rest, tap between tabs, swipe pages, press-and-drag across tabs) on this build.

## Previous task — OneMate glass material and bar bubble, 2026-10-12

Branch: `claude/liquid-glass-nav-bar-ktxdge`, based on `master@6ae90fb` (PR #84 merged). PR: [#85](https://github.com/igorcv88/WaEnhancerXCommunity/pull/85) (ready for review, not merged). No workflow dispatched.

Request: adopt OneMate's (kalicyh/OneMate) liquid navigation bar (a bubble that rises when pressed and can be dragged) and its glass material on every surface. The user's screenshots show the current app surfaces as dark, near-opaque slabs with a wide grey rim; they called the bar acceptable and the rest ugly.

Implemented (full audit: [docs/ONEMATE_MATERIAL_2026-10-12.md](docs/ONEMATE_MATERIAL_2026-10-12.md)):
- `KyantLens`: the OneMate/Kyant0/miuix material as RenderEffects. Saturation 1.5 → 4dp blur (Skia radius solved for miuix sigma 4dp·0.45) → one AGSL pass with an inward circle-profile edge lens (24dp), a surface tint (40% at Liquid's recommended opacity, moved by the slider, clamped 0.12–0.85) and the miuix dual-peak BloomStroke rim. Ported from the Apache-2.0 upstreams, not from OneMate (which has no licence); NOTICE updated.
- `GlassOptics.oneMate` and the new public key `liquid_glass_renderer` (default `onemate`; `classic` restores the previous renderer and every classic switch exactly). `LiveBackdrop` adds a OneMate capture path (1:1 + 3σ margin) for every pane/drawable surface; `LiquidLens.apply` installs it on the floating bar's and scroll button's BlurViews; `LiveBudget` charges 3 passes and the margin. Refused program → classic paths.
- `BubbleMotion` (pure, tested) + `LiquidBubble` replace `LiquidMorph` on the bar's Liquid style under OneMate: rest tint; on press of the selected tab it rises to 78/56 with lens depth, dispersion 0.5, rim and inner shadow, the bar widens by 16dp; dragging follows with velocity squash and a 4dp rubber band; release selects the settled tab via its own click (a 700 ms guard ignores WhatsApp's stale selection report). Tapping another tab animates rise→travel→land. The bubble records only preceding siblings in the capture-excluded host (BlurView output + accent-filtered tab row).
- Settings: "Material → OneMate material" switch on the Liquid Glass page; the optical-corrections caption says they apply to the classic renderer only. The synthetic lab is forced to the corrected renderer.

Validation: JDK 21/SDK 36 `:app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug` — **453 tests, 62 suites, 0 failures/errors/skips** (+`KyantLensTest` 6, `BubbleMotionTest` 10, 1 OneMate-default test; two classic-default tests now pin `classic`). `tools/agsl_check.py` passes all three shaders under glslangValidator (not proof of AGSL/driver acceptance). Debug APK `com.waenhancer.community` 1.8.0-alpha1/18001, debug-signed, SHA-256 `3e209c00aab4765d9185c20179a6254fa1765535b8d75074074955735fe26e50` (last build before docs-only changes). `git diff --check` clean. Build environment note: Maven Central returned HTTP 429 repeatedly; a machine-local Gradle init script pointing at Google's Maven Central mirror was used (not committed).

Not validated: no device run. Unknown: the real look over WhatsApp content in dark and light themes, bubble frame cost (it re-records while pressed), BlurView drawing into a foreign RenderNode on the user's ROM, and the hidden-API reflection on `mListenerInfo.mOnTouchListener` (if it fails, any WhatsApp item touch listener is not chained).

Open decisions / next steps:
1. Device acceptance per the checklist in the audit doc (S25 Ultra, Liquid style, opacity at the recommended value). Record the APK SHA and versions.
2. Decide whether to hide WhatsApp's native active-indicator pill under the bubble. This needs the view that draws it in the user's build.
3. Optional: tilt-driven highlight (sensor inside WhatsApp; deliberately not ported), and `InteractiveHighlight`.
4. If the tint is too strong or weak on device, tune `KyantLens.SURFACE_ALPHA` and the slider mapping, not the classic `GlassSpec` variants.

## Previous task — optical reconstruction, 2026-10-09

Branch: `feat/glass-optical-reconstruction`, based on latest `master@cbbf20205a6da6ee2bffda2879d1c8245bca1b1e`. Published as ready-for-review **PR #84**: https://github.com/igorcv88/WaEnhancerXCommunity/pull/84. Implementation commit: `f4d39686c916b45992aa1d737b69d2b60f83361d`; its tree `8ccb35807e71123254b67f8af55852402de95a44` exactly matches the locally tested/committed implementation tree. A documentation-only follow-up records this publication; no code changed after the final verified APK. No related open glass PR existed; the six open PRs were dependency updates. No merge or workflow dispatch is authorized here.

The user explicitly selected GPT-6.1 Sol High and authorized autonomous implementation, tests, compilation and an integrated review-ready PR using `LAUDO_DEFINITIVO_OTICA_LIQUID_GLASS_WAENHANCERX_2026-10-09.md` as the complete current specification. The older entries below are history. The reported stable capture behavior is preserved; Search was OFF in the relevant videos and remains user-selected.

Implemented:
- Four runtime profiles: exact V2 baseline transfer, balanced/strong beta-only candidates, and reconstruction. Existing preferences resolve to baseline. An explicit **Apply reconstruction candidate (Clear off)** action sets all optical groups/profile/debug/displacement consistently without changing styles, opacity or enabled surfaces. Changes apply on WhatsApp resume via the existing preference bridge/revision keys; the settings labels and synthetic lab refresh together.
- Reconstruction prefilters residual detail at native resolution, integrates a five-sample Jacobian footprint, mixes linear numeric weighted contributions through PLUS, then applies material tint, directional micro-highlight/broad reflection/internal occlusion and contrast in one final pass, with one output encoding and one shape coverage. This is a two-scale approximation, not EWA/mips or measured Apple equivalence. Existing source selection, warp caps, native controls, transforms, capture graph/recovery/scheduling are retained. The floating bar's established BlurView/View finish stays baseline to preserve the user's convincing reference.
- RAW_INPUT, SHARP_ONLY, SOFT_ONLY, beta, Jacobian, footprint, coverage, final-no-light, final and capture-generation diagnostics in the same APK. Soft isolation works even when Filtering is off. Reconstruction protection diagnostics measure the composed final pixel. Existing grid/displacement/legacy-warp diagnostics remain available; only diagnostic legacy warp folds.
- API33+ synthetic GPU lab renders known native-pixel bars/step/diagonal/colour fields with the actual LensEffect graph and uses PixelCopy to export isolated graphical stages/final PNGs through the document picker. It captures only the module's own synthetic view, never WhatsApp. Private cached PNG/saved state handle rotation during export. Metadata can be copied (profile/groups/radii/physical dimensions/density/API/device/module/generation/status). The lab uses a dark LIQUID spec; actual light-theme/native-control acceptance remains separate.
- LiveBudget charges the reconstruction graph at 6 padded pixel-passes versus historical 3, a conservative work estimate, not GPU time. No shared-source architecture, GPU downsampling or battery improvement is claimed. Blur documentation now distinguishes requested Android radius from an unknown effective HWUI sigma.
- `tools/glass_optics_metrics.py` analyzes native PNG ROI luminance/ESF/LSF/MTF and encoded footprint scales, rejecting invalid inputs and stating the stationary-edge assumptions. CPU pixel goldens cover geometry/transfer/footprint under an explicit ideal-Gaussian reference; they are not driver images.

Validation:
- Full local JDK21/SDK36 Gradle `:app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug` succeeded: **436 tests in 60 suites, zero failures/errors/skips**. The final settings-label/export corrections passed the same full incremental test and APK build. No source/dependency substitutes are used in the full build.
- `tools/agsl_check.py`: both legacy SHADER and corrected SHADER_V2 pass glslangValidator after AGSL-to-GLSL adaptation. This does not establish Android AGSL compiler/driver acceptance.
- PNG-metric synthetic smoke checks pass: native step/DC normalization, zero negative derivative share, rejection of an out-of-bounds ROI. `git diff --check` is clean.
- Debug APK: `com.waenhancer.community`, version `1.8.0-alpha1` / code `18001`, min API28 / compile API36, arm64-v8a and armeabi-v7a; 15,985,124 bytes. ZIP CRC and APK v2 signature verification passed. SHA-256 `ab841647c25940b50f7bd0e016567f078065bd40a0adb39dbebbce37d67058c4`. This is a debug-signed calibration build, not a release-key update.
- No device, GPU readback, visual A/B, frame benchmark or energy experiment was performed in this environment. The lab provides the mechanism to collect that evidence on the S25 Ultra; a shader/JVM/build pass is not visual acceptance.

Device next steps: [docs/GLASS_OPTICAL_CALIBRATION_2026-10-09.md](docs/GLASS_OPTICAL_CALIBRATION_2026-10-09.md) contains exact activation, diagnostic meanings, a compact A0–A6 matrix, native PNG/MTF instructions, capture and native-control regressions, and frame/GPU/energy acceptance. Run LIQUID, Headers/Input/Floating on, Search off, Clear off; compare baseline/balanced/strong/reconstruction before selecting a production material. Record APK SHA, module/WhatsApp versions, physical density/size and profile settings with each result. Validate actual alpha/colour/HWUI PSF and GPU cost, especially the new intermediate linear numeric contract, before declaring appearance complete.

## Historical task — 2026-10-06

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

## Receipts: delivery cancelled under Hide Read; reply release blocked by lookup — 2026-10-09

Branch `ccr-9c2dae1b-1qb2s6`, based on `master@b079ae2`; ready-for-review PR #82 (not merged). User report (screenshots, WhatsApp 2.26.33.76): Hide Blue Ticks on (forced and greyed by Send Blue Ticks upon Reply), Hide Blue Tick in Groups on, **Hide Delivered off**. Replying does not send blue ticks; only the manual "Send blue tick" menu does. Incoming messages appear to stay on one tick for the sender (the user cannot confirm delivered state from their side).

Causes (code reading, confirmed against upstream `Dev4Mod/WaEnhancer@91ba9fc8`; no device log):

1. **Delivery cancelled.** `HideSeen.hookEnforceHiding` hooks `ReadReceipts/sendReceiptForIncomingMessage`, the host method that sends the receipt of every incoming message, and set the result to null when Hide Read *or* Hide Delivered was on (`ReceiptPolicy.suppressRead`). With Hide Read on, no delivery receipt left: one tick. Upstream gates this method on Hide Delivered only. Manual release appeared to fix both because a read receipt implies delivery. The old `hiddenReadDoesNotHideDelivery` test asserted the buggy result.
2. **Reply release blocked.** Since #66, `SeenTick.hookOnSendMessages` returned before releasing anything whenever the outgoing-message lookup failed (missing `id`, key built only from the LID form, lookup miss, or any exception). The chat release therefore depended on the status-quote identity check. The manual path calls the same `sendBlueTick` without that lookup, which matches "manual works, reply doesn't". Which lookup step fails on the device is not proven.

Changes:

- `ReceiptPolicy.suppressIncomingReceipt(hideDelivered, viewed)` replaces `suppressRead`; the direct guard runs only for effective Hide Delivered (custom privacy/ghost included). Hide Read stays enforced by the SendReadReceiptJob hook and the protocol rewrite (`read` → delivery).
- `StatusReplyRouting.route(...)`: status broadcast → nothing; resolved outgoing quoting a status → that status only if it matches, else nothing; anything else (including an unresolved outgoing message) → chat release. Trade-off: if the lookup fails for a status reply, that contact's pending chat receipts are released (never the status). Status receipts still require a resolved quote.
- `findOutgoing` tries the LID and then the PN form of the destination key.
- `WaEnhancerX/Receipts` info logs: one `reply release route=… lookup=… jid=…` line per outgoing job run and one `chat release pending=N rows=M jid=…` line per chat release. Address form only (`lid+pn`, `group`), no numbers or IDs. `getHideSeenMessages` returning null no longer throws.

Validation: 24 JUnit tests (ReceiptPolicy, ReceiptRelease, StatusReplyRouting) compiled with javac and passed under JUnit 4.13.2. `git diff --check` passed. `SeenTick`/`HideSeen` were **not compiled** here: this container has no Android SDK and the wrapper's Gradle 8.14.5 is not cached. No APK, no device run, no workflow dispatched.

Next (device): install this head with the user's current settings. (1) Without opening the chat, have the contact send a message: expect two gray ticks on the sender. (2) Open the chat: still gray. (3) Reply normally (and once with a quote): expect blue on the sender without the manual menu. Capture `logcat -d -v time -s WaEnhancerX/Receipts:V`. If `route=CHAT` and `pending=0 rows=0`, the hidden rows are keyed differently (check `jid=lid` without `+pn`: LID→PN mapping missing). If no `reply release` line appears, `SendE2EMessageJob/onRun` is not the hooked path on this build. Also check a status reply releases only that status.

## Reply release still silent after #82 — 2026-10-09

PR #82 merged as `b718380`. Branch `ccr-9c2dae1b-1qb2s6` restarted from that master; ready-for-review PR #83 (not merged). Device result on #82 (user screenshot, same settings): **delivered now works** (sender sees two gray ticks). Replies (normal and quoted) still do not turn the sender's ticks blue, and the module's own indicator on incoming bubbles stays red. Red means a hidden-read row exists with `viewed=false`. A release through `sendBlueTick` would flip it green, so the reply path never reaches the release with matching rows. No logcat was supplied.

The failure predates #65/#66. The pre-#65 code had no outgoing lookup and the user reported the same symptom, so the lookup removed in #82 was not the only cause. The remaining candidates cannot be told apart by reading the code:

- (a) The method found by the log string `SendE2EMessageJob/onRun` is declared on a helper/lambda class, so `messageSendClass.isInstance(thisObject)` returned false on every send and the hook exited silently.
- (b) The job's `jid` resolves to a LID without a PN mapping, so the `jid=?` query (rows are keyed by PN) found nothing.
- (c) The hooked method is not on this build's send path at all.

Changes:

- At install, the hook logs the declaring class/method and whether it is declared on the job class (`declaredOnJob`).
- `findSendJob` takes the job from `thisObject`, an argument, or an instance field of the hooked object (covers a).
- `chatReleaseJid` uses the job JID when it has a PN form, then the outgoing message's own key (the form the bubble indicator reads), then the open conversation when it is the destination (covers b).
- Every skip and failure logs under `WaEnhancerX/Receipts`; hook exceptions are caught.

Validation: `git diff --check` passed. Not compiled (no Android SDK in this container). No device run.

Next: the user installs the head, force-stops and reopens WhatsApp, receives a message, replies, and sends `logcat -d -v time -s WaEnhancerX/Receipts:V` captured after the reply (no `logcat -c` after WhatsApp starts, so the install line is kept). Read it as:
- No `send hook on` line: the feature did not install.
- `declaredOnJob=false` plus `skipped: no send job`: case (a) needs another way to find the job.
- No line at all on reply: case (c); find another send hook.
- `pending=0 rows=0`: key mismatch; compare with the bubble indicator's key.
- `pending>0` but the sender still sees no blue: the read job is built or queued wrongly.

Also ask whether the manual "Send blue tick" still turns the dot green on this build.

## PR #102 voice recording and reply contrast follow-up — 2026-10-30

Branch `feat/glass-bubble-scope-and-header-split-20261030`, existing ready-for-review PR #102; keep its unrelated bubble/header/optical changes intact and do not merge or dispatch Actions.

User evidence: `86082.mp4` (2.14 seconds) captures the microphone action transition: the existing glass turns into a roughly screen-sized lensed circle before the native opaque black voice-recording panel appears. `86089.png` shows a quoted reply inside the blue-glass composer with low-contrast sender/snippet text. User requests one stable glass material with no static fallback, the same live composer style behind the recorder's native waveform/controls, and readable reply text, all in PR #102.

Source-backed suspected cause of the sphere: `GlassSurfaceCatalog` registered `conversation_entry_action_button` as COMPOSER (1000dp radius), although WhatsApp dynamically expands that action button on voice recording. Removed both this button and `voice_note_lock_container` from generic drawable routing; also excluded the resizing `voice_note_draft_layout_v2` from QUOTES while retaining its nested quote target. Actual runtime binding to the expanding native view has not yet been logged, so the exact source of the ball remains a strong hypothesis, not a device-proven fact.

`ConversationGlassPanes` now routes recording through the already-attached composer `GlassPane`: matches the timer-bearing, visible, wide non-input footer container, measures its bounds, clears its native background and only an optional full-bleed wrapper, and restores original backgrounds on exit/disable. Timer, waveform, trash/pause/send and touch targets are never reparented or recoloured. Unmatched layouts remain native and emit a one-time structural (ID/geometry only, no message content) diagnostic rather than adding a fallback. A visible reply preview receives 96% content-colour sender text and 86% content-colour snippet text, with native colour lists restored when reply closes. The material follows the active theme but does not sample wallpaper luminance continuously. `GlassSurfaceCatalog` regression coverage checks exclusion of animated controls.

Local verification: JDK 21 Java-parser check passed for the three edited Java files, `GlassSurfaceCatalog.java` compiled against the earlier isolated pure-Java test classes, the existing bubble/header/palette `Smoke` passed, and `git diff --check` passed. The Android APK and AGSL program could not be compiled in this environment because the Android SDK / compileSdk 36 is absent. The new voice code has no instrumented/native device test; a JShell test attempt was blocked by sandbox/class-loader setup, so it is not counted as a passing test.

Verification still required on Galaxy S25 Ultra / Android 17 / currently installed WhatsApp: recording route discovery, absence of the transient sphere, no black layer beneath the glass, waveform and controls, live scrolling behind the recorder, reply text in both light/dark and wallpaper themes, input and header glass persistence during swipe-back, no new glass rebuild loops or unavailable frames. Capture `WaEnhancerX/GlassState` and `[LiquidGlass/App]` logs after testing; check for `VOICE_RECORDING`, `voice-glass-bound`, and `voice-panel-route-missing` without exposing chat content. If recorder routing fails, inspect only the footer's reported semantic view IDs and geometry and adapt the bounded matcher; do not widen glass to every animated microphone control.

## PR #102 device follow-up — recorder delay, static footer strip, rim and bubble routing — 2026-10-30

Delivery note: PR #102 was merged by the user at `master@1a982af09240ef24015ce8c9daa9fa696db1595f` before these follow-up fixes were pushed. This work is in review-ready PR #103 (`fix/glass-voice-bubbles-optics-20261030`, base `master`), not appended to the merged branch. Do not treat the static code review below as an installed or validated build. No CI workflow was dispatched and no merge performed.

Static checks for this follow-up: JDK 21 parsed six edited Java files (including the changed KyantLens unit-test source); `git diff --check` passed and the previously compiled pure-Java bubble/header/palette `Smoke` passed. Those smoke classes do not exercise the new Android hooks, recording geometry, AGSL program, or new `uAppChrome` uniform. No Android SDK / compileSdk 36 available locally; full build, GPU shader compilation and phone tests remain pending. No workflow dispatched.

User attached `WaEX_GlassVoice_20261030_193616.txt` and `86121.mp4` (24.22 seconds). The recording shows the glass recorder catching up after its opaque native transition, a wallpaper-only strip under the action/microphone side of a tall reply/recording footer, a weak grey rim, barely perceptible app-chrome refraction, and message bubbles still native. The floating navbar look is explicitly approved and must not change. User also reports distracting content at the top of the conversation header and wants the name to remain legible.

Confirmed by trace: a previous PID (25805) still bound the old `conversation_entry_action_button` COMPOSER surface, but the new PID (31008) after installing the prior fix does *not*; this is earlier log-buffer history, not an active regression. On PID 2869, `VOICE_RECORDING` first bound `voice_note_stub` with geometry `39:25:1229:140` at 19:35:33.612, then `15:15:1425:429` at 19:35:35.354 (1.742s later). Multiple `radius-changed`/`size-changed` invalidations recaptured within milliseconds once they occurred. The delay is predominantly panel selection/geometry progression, not prolonged GPU recovery. Changing reply/input height from 167 to 335px also caused composer capture size changes, but `floatFooter` had frozen the message-list bottom-margin to the original height: this directly explains the no-list-content strip beside the input when the footer grows.

Bubble trace: `bubble-factory side=incoming wrapped` (and one earlier process reported outgoing) but no `bubble-attached` events or BUBBLES bindings. The factory returned glass, but there is no evidence the wrapper became a visible row background. The new code follows the native `Drawable.Callback` chain at first actual draw, schedules adoption on the UI thread, supports callback-owned nested rendering and emits a one-time `bubble-no-host-*` marker if no View callback exists. It also logs the resource IDs/classes/background types of at most three visible `conversation_row_text`/`conversation_row_voice_note` rows. No unverified row-wide glass binding is used. If these probes show the factory drawable was never drawn by the row, a WhatsApp-version-specific bubble hook or explicit verified mask owner is still required.

Implemented: measured footer-height-only margin updates; full-footer glass envelope from the first recognized voice timer (action disc kept native); full-bleed ancestor background clearing and reversible restoration. For the rim, app-surface refraction is 22.8dp instead of 15.6dp at the same 24dp depth and 30dp blur; app-surface local-luminance reflection screens toward clean white with a slight transmitted hue rather than darkening bright backgrounds. An explicit app-chrome shader uniform leaves the approved floating bar's optical branch intact. The header's single clipped pane applies a 12%-to-3% neutral top wash only when live pixels are present, suppressing detail behind the contact name without ever painting a fallback.

Still unverified: whether the callback-owned bubble route attaches on this WhatsApp build; exact colour and refraction after AGSL compilation; whether timer detection fires early enough on short press-to-record (as opposed to locked recording); whether there is any voice black fill outside the cleared ancestor chain; smoothness and absence of new geometry oscillation after reply/voice transitions. Need next build, installation, fresh short recording with scroll and reply, `WaEnhancerX/GlassState` and `[LiquidGlass/App]` logs including `bubble-row-structure`, `bubble-attached`, `bubble-no-host`, `VOICE_RECORDING`, and a few screenshots. Do not dispatch Actions or merge until user explicitly authorizes.

## Post-merge #103 follow-up — recorder event, duplicate quoted preview, native row bubbles, release selector — 2026-10-30

PR #103 was merged at `master@b003dac6631eed61f6c0571f74341a761277b34c` before these edits. New review-ready **PR #104** (`fix/voice-start-reply-bubbles-release-20261030`, initial head `348bc25b2c4af68ea51081c2968577bd8a8a6039`) targets `master`, independent of merged #103. This handoff update is the final documentation follow-up on the same branch. No Actions workflow was dispatched or merge performed.

User video `86153.mp4` (21.07s) and `WaEX_Glass103_20261030_200225.txt`: recorder glass eventually appears, but only after subsequent screen updates; when recording while replying, two native copies of the same quoted preview appear vertically (the lower one overlaps the elapsed-time/waveform section). Scrolling behind the expanded footer, header, bright rim and app-profile refraction are now approved and must stay unchanged. Bubble glass remains absent.

Strongest bubble evidence: `bubble-factory side=incoming/outgoing wrapped` followed by `bubble factory drawable was drawn without a View callback`, with zero `bubble-attached` events. Three visible `conversation_row_text` and `conversation_row_voice_note` rows expose a native `Fyn` drawable in their *row* background plus a `main_layout` child. Thus wrapping the factory alone cannot establish ownership. The new narrowly scoped row binder retains `Fyn` as a native alpha mask, crops glass recording to the measured content's area, and uses relative horizontal alignment to select incoming vs outgoing. It skips unknown row types/classes, missing or ambiguous geometry and disabled bubble scope. It restores native content on disable/recycling. This is a reasoned source+trace adaptation, **not yet proven on a device**; verify that `main_layout` represents the speech bubble and that masking/capture have acceptable performance. If the next trace lacks `bubble-attached-row-*`, collect bounded row child geometry and background class evidence, without harvesting message content.

For voice, `AppLiquidGlass` now hooks semantic recorder `setVisibility`, `ViewStub.inflate`, and the actual `TextView.setText(CharSequence, BufferType)` elapsed-time path. `ConversationGlassPanes.onRecorderSignal` checks the native recorder ancestry, synchronizes the *same existing* composer pane immediately and posts one animation-frame retry. A 900ms signal can select a visible wide `voice_note_stub` before its first timer text. This removes the dependency on unrelated layout/touch events; measured on-device startup latency remains unverified. If both normal composer and draft quoted previews are visible during recording, the distinct `voice_note_draft_quoted_message_frame` is made visually transparent (alpha restored when recording ends), with its layout and WhatsApp reply semantics untouched. Verify on the device which exact quote is preserved and whether native click targets remain sensible.

Release workflow `.github/workflows/build-apk-release.yml`: deleted the `mode` choice, SHA input and complete `lab` job; the one manual release job now always requires `master`. `channel` choices are `stable` (default, first) and `beta`. No workflow execution was requested.

Available local checks: JDK 21 Java-parser validation for three modified Java sources, bubble/header/palette pure-JVM `Smoke`, four app-only runtime regression tests, workflow YAML parsing and `git diff --check`. No SDK / compileSdk 36 locally, so no Android compile, AGSL runtime, frame timing or device test; optic shaders were not touched. The next physical test should record a voice note on a static screen immediately, do the same while a reply is prepared, switch back to input, then scroll rows and test incoming-only/outgoing-only/both glass scopes. Collect `WaEnhancerX/GlassState` and `[LiquidGlass/App]` messages for `voice-recorder-signal`, `VOICE_RECORDING`, `bubble-attached-row-*`, capture budget/refusals and errors. A successful parse is not evidence the Xposed hooks fired.

## Post-merge #104 follow-up — fullscreen, first voice frame, duplicate quote and bubble presentation — 2026-10-30

PR #104 has already been merged into `master@af61aff08898721eba85f6dcb7a15c25b55c9903`. The user reports that bubble pixels still look native, the double quoted reply persists during voice recording, the little blinking red microphone is below its intended position, and the recording's glass still arrives after several seconds (though without requiring a touch). The contact name sits too close to the status bar; the user now explicitly wants the status bar hidden and the conversation to occupy the display. Published ready-for-review **PR #105** (`fix/conversation-fullscreen-voice-bubbles-20261030`, implementation commit `b1cf362140feeb5fe62f284da91f03f8817d101a`) against that merged master. No Actions workflow was dispatched or merge performed. The previously approved clean rim, refraction, input and scroll-under-footer remain unchanged.

Device log `WaEX_PR104_20261030_202147.txt` (20:20–20:21) establishes `Fyn` masked BUBBLES row bindings for both directions and successful GPU captures (`RECORDED`, `hasDisplayList=true`, measured crops), but new bindings frequently entered `BUDGET_REFUSED`. It does *not* establish visible `PRESENTING_LIVE` pixels over WhatsApp's Canvas-owned bubble factory. Two recorder sessions bound an initial short geometry (`15:15:1262:152`), only expanding to `15:15:1425:429` about 0.8–4 seconds later, despite the native voice signal. These facts disprove the former missing-hook explanation for all bubbles and isolate a recorder geometry/first-presentation gap. Old `FATAL EXCEPTION` lines from 10/29 in the supplied log buffer cannot be attributed to this build.

Changes: the same persistent composer pane now extends above a short native recorder footer to an anticipated 140dp voice envelope plus visible quote height, with recorded `clipChildren` settings restored on exit; native recorder visibility also explicitly invalidates the pane/footer to wake the first draw. Exact known composer/draft quote IDs are paired first; otherwise two distinct visible, wide semantic quote containers are matched by ID/geometry without requiring two TextViews, and only the lower duplicate's pixels are temporarily suppressed. Small native recording microphone ImageViews are matched by name or bounded position near the elapsed timer and vertically aligned; absent matches emit resource names/geometry, not text or audio data. Unknown runtime IDs still need a device trace if the heuristics fail.

The Activity window reference comes from the existing RESUMED lifecycle callback. For an enabled conversation contact header only, the code hides the status bar, preserves the navigation bar/IME, allows the cutout's short edges and shifts the header down by the recorded safe top inset plus 8dp. The previous fullscreen flag, cutout mode and view margins are restored when the conversation header is removed. This window behavior is not applied to home, media viewers or calls.

Bubble binding now considers `conversation_row_*` types for photos and captions but still requires native `Fyn`, measured asymmetric `main_layout` and direction scope; generic arbitrary rows are not re-skinned. New rows are budget-ordered from the visible bottom toward the top, while existing sticky admissions stay persistent. On the first successful GPU capture, the row is explicitly invalidated until `GlassMaterialDrawable` records a live draw; this distinguishes a cached background-display-list gap from native overpainting in the next device trace. It **does not establish visual acceptance** of bubbles, which may still be painted over by WhatsApp's Canvas-owned factory. No static/opaque fallback was introduced.

Static validation: JDK 21 parser passed for `ConversationGlassPanes`, `AppLiquidGlass`, `GlassMaterialDrawable`; `Smoke` passed and `python3 tools/test_app_only_runtime.py` passed four tests; `git diff --check` passed. Full Android compile (compileSdk 36), AGSL/GPU and device checks unavailable. Next test on Galaxy: stationary screen → press voice record, verify glass begins on first frame; do reply→record and count previews; verify red mic location; enter/leave a contact chat and confirm status bar restoration; scroll text/media bubbles and check native clipping and performance. Capture `voice-reply-candidates-*`, `double-voice-reply-*`, `voice-mic-*`, `VOICE_RECORDING`, `PRESENTING_LIVE`, `PRESENTING_TRANSPARENT` and budget events for the active process only. No workflow dispatched and no merge requested.

## Review-comment audit and PR #105 follow-up — 2026-10-30

Audited glass-related PR discussions #69–#105, including the open review-ready #105, and checked surviving findings against current sources. Actionable inline comments: #73 (`4220448725`, duplicated own-scroll translation on background capture); #84 (`4234499196`, padded optics lab samples flat margins); #102 (`4428714959`, nested bubble replacement after a recycled row stays unbound); #104 (`4429261622`, native bubble discovered before `main_layout` measurement is never retried); and #105 (`4429378942`, `FLAG_FULLSCREEN` suppresses `SOFT_INPUT_ADJUST_RESIZE`). Older issues regarding fallback/teardown, bubble motion, Home glass restoration, and selector state had been addressed by later changes or their classes were removed; no duplicate patches were applied to those obsolete contexts.

Corrections in the same PR #105: when a nested bubble binding is displaced, detect and adopt a newly mounted wrapped drawable without waiting for another discovery pass. Keep weak pending candidates for measured `Fyn` conversation rows and retry those on the next scan after layout; release candidates after binding or valid measured classification. Remove `FLAG_FULLSCREEN`; use the status-bar-only insets controller with remembered previous bar visibility/behavior and preserve the keyboard's resize capability. Remove redundant own-scroll translations in `GlassPane.drawPlaced` and `BehindRecorder.paintExact`; `View.draw()` already applies its own content scrolling after global placement. Extend optics-lab stripe/colour fields through the padded recording bounds so the lens's synthetic edge samples actual calibration content.

Device confirmation is still required: recycling between message types; first layout and scroll for image/text/voice rows; composer movement with the IME while contact status bar is hidden; swiping away and back to restore prior bar visibility; nonzero-scroll capture alignment and calibration lab edges. The prior PR #105 voice-start, quote duplication and microphone-position work is not claimed fixed by this audit. No release workflow or Actions run is requested as part of the review audit.
