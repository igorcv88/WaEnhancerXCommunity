# Roadmap

This is the single forward-looking engineering plan for WaEnhancer Community. Historical `HANDOFF_*`, `PLAN_*`, and review documents described implementation sessions and intermediate repository states; their useful remaining decisions are consolidated here and the original text remains available in Git history.

## Current baseline

The following work is considered implemented and should not be reopened as unfinished merely because an older plan still describes it:

- Community identity and removal of the closed Pro/Helper/Firebase runtime paths.
- Typed preference schema, public/private preference separation, migration and rollback support.
- UID-aware providers and token-gated Tasker message sending with rate limiting and deduplication.
- Allowlisted configuration backup/import and password-encrypted full backup for `Deleted for Me`, schema-defined private secrets, and optional preserved media.
- `Deleted for Me` database and preserved-media vault.
- Local redacted diagnostics plus explicit runtime validation sessions.
- Floating bottom bar editor, presets, preview, geometry normalization, and selective Liquid/Advanced Glass rendering.
- CSS validation, size/image limits, temporary test mode, last-known-good rollback, and safe mode.
- Semantic color-token engine and preset themes.
- Central settings-icon registry and injected settings icons.
- Verified APK update path with package/signature/version/digest checks.

## Active work

### Element Inspector

Status: implementation exists on `feat/f2-element-inspector`, but it is not present on `master`.

Do not merge the stale branch wholesale. Port the feature onto a fresh branch from current `master`, preserving the useful implementation:

- inspect a touched WhatsApp `View` without reading message text;
- build selectors that match the existing `CustomView` CSS dialect;
- highlight the selected view in-process without `SYSTEM_ALERT_WINDOW`;
- expose copy-ID/class/selector actions and a temporary session with expiry;
- keep the pure selector, redaction, hit-test, and session logic covered by JVM tests.

Modernize the port against the current codebase:

- use the current cross-process `SafePrefs`/provider bridge for session state instead of accepting the old branch limitation where an `XSharedPreferences` session could not be cleared from the WhatsApp process;
- reuse the current diagnostics/validation UI conventions where useful, but keep Inspector state separate from `ValidationSession` because Inspector state must be readable by the hooked process;
- re-integrate `MainActivity`, `FeatureLoader`, `PreferenceSchema`, resources, and strings against current `master` instead of restoring older versions of those files.

Device validation is mandatory before merge: selector correctness, Activity changes, rotation/split-screen, no `WindowLeaked`, navigate/pick touch behavior, timeout, WhatsApp and WhatsApp Business.

### Tasker configuration UI

The hardened Tasker protocol exists, but the user-facing configuration required to use it safely is still missing.

Add a settings surface that can:

- enable/disable the integration;
- show/copy and regenerate the per-installation token with an explicit warning that it grants message-sending capability;
- manage the allowed-package set;
- control whether outgoing automation events may contain the message body;
- expose the temporary legacy unauthenticated mode only with a strong warning, and remove that compatibility mode when its migration window is over.

Do not weaken the token boundary to make setup easier. A broadcast does not provide trustworthy sender identity; the package name is defense in depth only.

### Visual theme override editor

The semantic theme engine is implemented, but per-token user overrides are not.

Build the editor on top of `SemanticTheme` rather than creating another color engine. The intended behavior is:

- separate light/dark override maps;
- curated common tokens first, advanced tokens only when the runtime actually consumes them;
- a static conversation preview driven by the same resolution code as production;
- per-token and global reset;
- measured contrast warnings with an explicit one-tap correction, never silent mutation of a chosen color;
- defensive parsing and a bounded public preference payload suitable for backup/export.

With no overrides, resolved colors must remain byte-for-byte equivalent to the existing preset behavior.

## Deferred or conditional ideas

### `You` shortcut in the WhatsApp bottom navigation

No equivalent implementation exists on `master`. The reduced design (a bottom-nav shortcut that opens the user's profile rather than a synthetic `ViewPager` page) is feasible, but it adds another set of hooks to an already version-sensitive navigation surface. Re-evaluate the UX value against current WhatsApp before implementing it. It is optional product work, not unfinished infrastructure.

### Portrait/landscape profiles and quick preset switching

These were intentionally excluded from the earlier F3 scope. Orientation-specific profiles multiply preference state and require a real migration strategy. Quick preset switching has no current dependency that requires it. Keep both deferred unless there is a concrete user need.

### Additional Liquid Glass surfaces

Do not treat the old goal of applying glass to many surfaces as a checklist. The implementation now uses a better rule: add a surface only when content actually moves behind it, readability remains acceptable, and device measurements justify the render cost. The message input row was tested and intentionally rejected under this rule. New surfaces should be added only after the same measurement/validation process.

### Animated selected-tab indicator

Closed. The previous implementation produced a second indicator over WhatsApp's native indicator and was removed. Do not reintroduce it unless the native active-indicator path can first be identified and controlled directly.

## Maintenance policy

Dependency updates should be split when they cross compatibility boundaries. In particular, Android Gradle Plugin/Gradle major upgrades must be verified against build plugins before merging, and Android libraries must be checked against the project's `minSdk = 28` rather than accepted as a grouped Dependabot update solely because newer versions exist.

GitHub Actions versions should be kept current enough to avoid deprecated runner runtimes. Release-workflow changes must preserve signing checks, manifest/package verification, SHA-256 publication, manual dispatch, and the Stable/Beta release semantics used by the app.

## Release validation still required

CI proves compilation, JVM tests, R8/signing checks, and static contracts. It does not prove runtime behavior inside a changing WhatsApp build. Before promoting a build, exercise at least:

- startup and core compatibility probe on the target WhatsApp/Business versions;
- embedded and standalone settings navigation;
- update discovery/download/verification for both release channels;
- floating bar and enabled glass surfaces;
- CSS save/test/expiry/rollback/safe-mode flows;
- `Deleted for Me`, preserved media, configuration backup and full backup/restore;
- Tasker after its configuration UI is implemented;
- features whose latest compatibility work is marked compile-verified but not device-verified.
