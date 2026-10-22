# WhatsApp/Business application-only review

Fork baseline: `igorcv88/WaEnhancerXCommunity@4039cecaec7fb63d56a28f6f7191e3cb590f6f95`.
Upstream reference: `Dev4Mod/WaEnhancer@f4a8da1eb4bb5cf9672983909efdf337269369ec`.

The current upstream Kotlin/Yuki entrypoint still loads Patch and ScopeHook into the system process and uses its SettingsProvider bridge. It also changes Xposed API/runtime architecture. None of that runtime was ported: Community keeps repository-built executable code, its legacy API-82 entrypoint, public/private preference schema and existing feature runtime.

## Process and feature decisions

| Component | Before | Application-only change |
| --- | --- | --- |
| `WppXposed.handleLoadPackage` | Global hooks before host check | Positive allowlist before app helpers: WhatsApp, Business, Community companion only. Stale Android/Settings/Google scopes return without registration |
| `ScopeHook` | ServiceManager, AppsFilterBase/AppsFilter visibility exception and SettingsProvider.call relay | Implementation deleted; provider called directly |
| `AndroidPermissions` | Unused experimental PermissionManagerService hooks | Deleted; no permission-policy hook remains |
| `Patch` | PackageManager downgrade policy | Deleted; OS downgrade policy stays native |
| `AntiUpdater` | PackageInstaller session interception in arbitrary scoped apps | Deleted; no module policy blocking WhatsApp updates |
| `ProviderClient` | Activity trampoline and Settings.System call | Direct explicit module provider URI, one shared in-flight future, bounded timeout and stale-result rejection; no restart loop |
| `BridgeClient/BridgeService` | Alternative exported service and activity start | Removed; file operations keep the same AIDL contract through the authenticated provider |
| `HookProvider` | Schema allowlists and UID gate | Gate stays before clearing identity; preference operations, migration and notifications retained |
| `HookBinder` | Generic file operations, caller checked only at acquisition | UID authenticated on every transaction; canonical path/type capabilities, descriptor revalidation and least-privilege open modes; enumeration refused |
| `initZygote` | Optional global debug properties | Only retain module APK path; no writes or app helper initialization |
| `HookBL`, `AntiWa`, spoof UI/search | Optional attestation and root/emulator spoofing | Deleted from runtime/UI/search; stored values/secrets remain intact for rollback, never automatically enabled |
| App UI, privacy, receipts, media, downloads, embedded settings | Host feature loader and direct preference provider | Preserved; no identified feature-specific system_server dependency |
| `disableSecureFlag` | Host-local capture behavior | Unchanged; outside this system-hook refactor |

The scope array is exactly `com.whatsapp`, `com.whatsapp.w4b`, `com.waenhancer.community`. The old companion scope incorrectly named `com.waenhancer`. All source resource qualifiers, source sets, Manifest metadata and the compiled APK are checked; no modern scope.list may add another process. The existing forceQueryable application flag and package queries are retained, with no new exported component or permission.

Direct provider access already carries configuration/secret operations in the existing code. Moving Binder acquisition to the same URI removes the privileged relay without broadening CallerAuthority. Android 17/Samsung package visibility still requires a physical check. A visibility failure must be reported as a bridge failure, never repaired by reintroducing global AppsFilter hooks, world-readable preferences, or trusting a package name in an Intent.

## Validation

`python3 tools/test_app_only_runtime.py` compiles the actual WppXposed, AppOnlyScope, CallerAuthority, HookBinder, BaseClient and ProviderClient against small JVM stubs and executes them. It checks zero hook registrations and zero app-helper initialization for Android/system/Settings/Google/Wallet scopes; host and companion dispatch; UID rejection for root/system/shell/unrelated callers; accepted WhatsApp/Business/self; direct provider URI; connection reuse; timeout rejecting a late Binder; successful subsequent reconnect. Resource/Manifest and removed-code checks are separate test methods.

These tests do not emulate Binder identity propagation, Android provider startup, Samsung package visibility, WhatsApp internals, UI hooks or a real framework. Existing JVM tests must also pass in the LAB build. `verify_apk_profile.py` separately checks final Manifest, scope resource configurations, Xposed entrypoint and DEX markers. CI must produce and verify the APK before this PR is treated as a compiled candidate.

Local runtime harness: 3 test methods passed. Local Gradle compilation was blocked by an uncached Gradle distribution/network access and missing Android SDK. No APK was produced locally and no phone was changed.

## Manual LAB build, release and rollback

The existing workflow remains workflow_dispatch only. `mode=lab` (default) requires `expected_sha` to equal the full selected branch head, runs source/runtime and existing JVM tests, builds a release-optimized APK with the configured release key, verifies signature certificate/alignment/compiled profile, and uploads APK + SHA-256 + source SHA as an Actions artifact. It never tags, merges or publishes a GitHub Release. `mode=release` retains master-only public release/version/integrity checks and adds the application-only gates. The LAB keeps the Gradle baseline versionCode; it may be lower than an installed public release. Do not uninstall or downgrade an existing installation merely to test it.

Before any installation, retain the previous working module APK/config backup and record enabled LSPosed scopes. On the target phone, test only WhatsApp/Business/companion: Android/framework and SettingsProvider must be unselected. Force-stop/reopen WhatsApp when sufficient; follow the existing safe reboot procedure if old global hooks were already installed in running processes.

Check cold-start provider/config hydration with the companion process stopped; change a public preference both ways; restart the companion while WhatsApp is open and exercise reconnect; check private keys are absent from public enumeration; test save/download and custom wallpaper/theme file reads, messages, notifications, calls, receipts/privacy, embedded settings and Business if installed. Confirm no restart loop when companion is disabled/unavailable. Re-evaluate native integrity/certification/Wallet through the same existing workflow without clearing Google data or adding integrity modules.

If a regression appears, stop changes, retain logs, disable only the experimental module and restore its prior APK/scopes if needed. Keep user data and the existing root/framework setup. No integrity or Wallet outcome is asserted from source tests.

## Security follow-up before the LAB build

The original UID-only file bridge was insufficient: an authorized WhatsApp process could request a private module path. The bridge now grants narrowly defined operations rather than arbitrary file access:

- `openFile(create=true)` opens write-only media outputs in the configured primary shared-storage download directory, under `WhatsApp` and the seven categories actually used by DownloadViewOnce, DownloadVideoNote, DownloadProfile and StatusDownload. Only the supported image/video/audio extensions are accepted. No XML/DB/config/script files or arbitrary nested folders.
- `openFile(create=false)` opens only existing image files in `Download/WaEnhancerCommunity/themes` or the selected shared-storage wallpaper. Reads are read-only. The two consumers are WallpaperView and CustomView.DrawableCache; neither needs write access.
- Both operations canonicalize paths and reject `..`, Android app storage, private module paths, sibling-prefix paths and symlink escapes. Configured paths are never exempt from the trusted primary shared-storage boundary. Open uses O_NOFOLLOW and validates the opened regular-file descriptor via `/proc/self/fd` before returning it or truncating an output. Failed validation closes both descriptors.
- `createDir` is confined to the media output root/categories. `listFiles` has no production bridge consumer and is refused for every caller; its AIDL method remains only for ABI compatibility.

HookProvider now permits write/remove only for known non-secret `Store.PUBLIC` keys. Both the transport type tag and raw Bundle value must match `PreferenceSchema.Entry.type`; malformed or unknown inputs are rejected before editing. There are no private-write exceptions. Embedded controls for private settings reuse the existing open-in-module row, so editing remains in the companion instead of falsely showing a successful bridge write. Existing explicit named secret operations for transcription/Tasker are unchanged; generic reads/enumeration and file access never expose the private store.

The three obsolete spoof controls were also removed from `res/raw/waex_settings_map.json`, not only the XML preference screens and search index. Regression coverage parses this map and checks its preference value types against the schema.

ProviderSharedPreferences now retries failed hydration and observer registration on later reads with a monotonic 500 ms attempt limit. Only an authoritative, committed provider snapshot marks it synchronized. A throttled notification remains invalidated so its last update is not lost. Cached data is retained during failure; after recovery, an application-startup-only hook may still require a WhatsApp restart. No automatic process restart or hook reinstallation is introduced.

Validation: four local test methods passed. The executable harness now compiles actual HookProvider, HookBinder, schema/policies, ProviderSharedPreferences and DeferredProviderSharedPreferences in addition to the entrypoint/client/auth classes. It exercises all 26 private schema keys, valid and malformed public values, path traversal/private paths/databases/symlinks/prefixes, open modes and rejection before truncation, cold-start failures/retry, settings updates, observer recovery and companion loss/return. Java canonical-file and symlink checks run on real temporary files. Android Binder/ContentResolver/Os/descriptor behavior is substituted by host stubs: this is not a native Android security or functionality test. The complete LAB build and on-device API-37 checks remain required; the old expected_sha is superseded by the latest PR head. No phone changes, merge or release.
