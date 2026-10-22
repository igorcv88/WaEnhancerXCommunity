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
| `HookBinder` | Generic file operations, caller checked only at acquisition | UID authenticated on every transaction, including a transferred Binder; existing media/theme file contract retained |
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
