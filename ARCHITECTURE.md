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

## Themes and visual customization

The visual stack has three separate responsibilities:

- `SemanticTheme` derives semantic color tokens from presets/accent colors with contrast safeguards;
- `CssSafetyManager` validates user CSS, limits size/image references, provides temporary testing, last-known-good rollback, and safe mode;
- `GlassSpec`/`GlassRenderer`/`GlassSurface` implement open glass materials. Liquid Glass is applied selectively to surfaces whose backdrop and performance justify it rather than as a global effect.

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

Official releases are produced by the manually triggered GitHub Actions release workflow with JDK 17. The workflow validates the Gradle wrapper, signs the release APK, verifies certificate/package/version metadata, calculates SHA-256, and publishes the APK directly to a GitHub Release.

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
