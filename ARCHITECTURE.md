# Architecture

## Base and identity

WaEnhancer Community is an independent GPLv3 fork based on the upstream WaEnhancer stable 1.7.0 line. Later upstream code is treated as a reference source and ported selectively when it fits the Community architecture.

The installed application ID is `com.waenhancer.community`. The Java package/Android namespace remains `com.waenhancer` where changing it would add compatibility risk without changing the installed identity.

## Runtime model

The project runs in two main contexts:

- the standalone module application, which owns settings, local UI, backups, updates, migrations, diagnostics, and private storage;
- the injected WhatsApp or WhatsApp Business process, which resolves and installs approved hooks and reads only the configuration needed by those hooks.

Executable module code is built from this repository. The Community fork does not depend on a closed-source Helper APK, an external DEX, or an externally loaded native library for its feature runtime.

## Preferences and storage

`PreferenceSchema` is the source of truth for preference type, storage class, sensitivity, exportability, defaults, and bounds.

There are two preference domains:

- the public/default store contains non-secret configuration that the hooked process must read through the cross-process preference bridge;
- `private_config` is `MODE_PRIVATE` and contains secrets, migration state, updater internals, caches, and other module-only state.

A hook that needs a secret obtains it through a narrow provider operation rather than by placing the secret in the public store. `SafePrefs` and the provider-backed preference bridge are used when the hooked process must safely propagate a state change back to the module process.

Private databases/files hold `Deleted for Me` records, preserved media, migration snapshots, and local diagnostics.

## IPC and automation

Cross-process entry points use capability-specific contracts rather than generic storage access.

- `HookProvider` exposes schema-approved configuration and validates callers before privileged operations.
- `DeletedMessagesProvider` exposes only the deleted-message/media operations it needs and validates callers on every operation.
- exported activities/services that must be launched from the hooked process are explicit and documented.
- Tasker message sending is disabled by default and protected by a per-installation secret, rate limiting, deduplication, and an optional package allowlist. A broadcast does not provide trustworthy sender identity, so the token is the security boundary and the declared package is defense in depth.

## Hook loading and compatibility

WhatsApp internals are version-sensitive. Resolvers must fail closed for the affected feature rather than turn a missing method or field into a process-wide crash.

The runtime diagnostics layer records resolver/install/trigger evidence without collecting message content. `ValidationSession` lets the user explicitly run a local functional validation session against WhatsApp or WhatsApp Business and preview/copy the result.

Compatibility evidence is scoped to the installed WhatsApp build and module build. Absence of evidence must not be promoted to a false incompatibility claim.

The injected `MessageHistory` SQLite store treats `viewed` as explicit receipt-release authorization. Its DB/cache operations are serialized so delayed privacy callbacks cannot revoke that authorization. Known legacy message-key schemas (versions 4–6) migrate to version 7 transactionally while retaining original receipt rows in an archive table. Unmapped row-ID schemas and unknown downgrades preserve their source files; they require a validated migration before their old history can become active.

## Themes and visual customization

The visual stack has three separate responsibilities:

- `SemanticTheme` derives semantic color tokens from presets/accent colors with contrast safeguards;
- `CssSafetyManager` validates user CSS, limits size/image references, provides temporary testing, last-known-good rollback, and safe mode;
- `GlassSpec`/`GlassRenderer`/`GlassSurface` implement open glass materials. Liquid Glass is applied selectively to surfaces whose backdrop and performance justify it rather than as a global effect.

Live app-surface glass uses `GlassPane` (ported from WaThemer): a view inserted beneath the surface, never a replacement of the surface's background. Each pane records only the region behind itself on the GPU, from a named source subtree (such as the message list) over its wallpaper underlay, and `LiquidLens` refracts and blurs that recording. `GlassPaneGraph` refuses any source that contains the pane, directly or through another pane: a recording that reaches its own pane is a display-list cycle that crashes HWUI's RenderThread natively, outside any Java error handling. In the conversation screen the header and footer float over the list, which is padded with `clipToPadding=false`; every layout change is recorded and restored when the surface is switched off. Every other surface uses `GlassMaterialDrawable` in its own background slot, made live by `BehindRecorder`: it records only what the window draws beneath that view (ancestors' backgrounds and the siblings drawn before its branch, by Z then index), never the view or anything after it, so no two surfaces can record each other. Both paths share `LiveBackdrop` (a GPU display-list recording, `LiquidLens` as a `RenderEffect`) and fall back to `GlassSpec.withoutOptics()`, a neutral translucent pane with no accent glow, in power saving or where the lens is unavailable. The legacy recording scales the display list by 1/4 and back; that does not lower the rasterisation resolution and only adds a rounding error, so it must not be described as a downsample.

Optical corrections are a second renderer behind `GlassOptics`. The user switches them in the Liquid Glass page, `config.LiquidGlassOptics` stores them, and `AppLiquidGlass` publishes them on every resume; every provider (panes, drawables, the floating bar's `GlassSurface`) compares `GlassOptics.revision()` and rebuilds its effect on the next frame. They are off by default; off is the legacy shader, capture scale and scheduling logic (the scheduling now lives in shared classes, so the logic is the same but the code paths are not literally identical).

Stable geometry is an invariant of the corrected renderer, not a switch: a bounded, fold-free warp (`LensModel` mirrors the shader; `LensJacobianTest` requires σ_min ≥ 0.30 per channel, measured 0.335 with the amplitude cap at 0.33 of the bevel), 1:1 recording, and exact `transformMatrixToGlobal` placement. The folding legacy warp is reachable only as a diagnostic view. Four groups can be switched independently:
- **Filtering.** A platform Gaussian low-pass mixed with the sharp input by depth, through a two-pass `RenderEffect` graph in `LensEffect`. Each pass carries its own share (soft β, sharp 1−β), including coverage, and the passes are added (`BlendMode.PLUS`), so coverage is applied exactly once. The recording is padded by 3σ, and `BehindRecorder.paintExact` culls siblings against that padded rect and draws them in Z order.
- **Adaptive contrast.** It needs filtering. In-shader protection toward a contrast target, applied last, after lighting, in linear light, in both passes. Luminance is linear in the composite, so the result meets the target wherever both passes do, up to the protection cap. The target is the theme's content colour; grey hint and secondary icons are not guaranteed.
- **Colour.** Linear light, `layout(color)` uniforms, and calibrated saturation, dispersion and highlights.
- **Temporal.** Damage-driven capture (`CaptureScheduler`): no periodic heartbeat, one trailing frame at rest. Effects keyed on full material equality. Interaction state kept across material changes. A per-window `LiveBudget` in effective pixel-passes (padded area × passes) that admits panes and drawables alike.

The corrections change optics and scheduling only, never what may be recorded: the acyclicity guards (`GlassPaneGraph`, `BehindRecorder`) are shared by both renderers. If a device refuses to compile the corrected program, or it fails three times, surfaces drop back to the legacy lens; a native RenderThread failure is outside what this can catch.

The floating bottom bar has its own editor and preview but resolves shared glass material through the same rendering model. Bottom sheets can opt into glass through the shared dialog helper.

## Deleted data and backups

`Deleted for Me` uses a private SQLite store with incremental migrations. Preserved media is stored through a private vault with integrity metadata and quotas.

There are two backup classes:

- configuration backup is versioned and allowlisted and deliberately excludes secrets/internal state;
- full backup is portable and password-encrypted, validates the authenticated payload before mutation, includes `Deleted for Me`, schema-defined private secrets, and optional preserved media, and restores database/preferences transactionally.

Migration and restore code must preserve the source until the destination has been validated.

## Updates and releases

The app reads release metadata from `igorcv88/WaEnhancerXCommunity`. Stable and Beta are explicit channels identified by the release tag/version convention.

Downloaded APKs are verified before installation: published SHA-256, package identity, signing certificate, and version policy. Downgrades require an explicit user action.

Official releases are produced by the manually triggered GitHub Actions release workflow with JDK 21 and JVM 17 application bytecode. The workflow validates the Gradle wrapper, signs the release APK, verifies certificate/package/version metadata, calculates SHA-256, and publishes the APK directly to a GitHub Release.

## Invariants

- never place a secret in configuration readable by the WhatsApp process;
- never load closed executable code into the module or target process;
- no analytics or automatic remote crash reporting;
- no embedded private tokens or signing material;
- no generic exported provider operations when a narrow capability will do;
- no destructive database/preference migration fallback;
- preserve user data before tightening storage or IPC;
- isolate compatibility failures to the smallest feature possible;
- keep forward-looking work in `ROADMAP.md`; historical implementation plans belong in Git history, not in the repository root.
