# WaEnhancer Community

WaEnhancer Community is an independent GPLv3 fork of WaEnhancer for Android. It is an Xposed/LSPosed module that modifies supported WhatsApp builds with privacy, interface, media, and automation options.

This project is not affiliated with, endorsed by, or supported by WhatsApp, Meta, or the upstream maintainer. Modified clients and runtime hooks can break after WhatsApp updates and may create account, privacy, or stability risks.

## Development status

The Community fork is being rebuilt from the upstream `1.7.0` release commit `433a1c630bc1286f2db3c657a63f477aa0aa426d`. The later beta branch is used only as a selective reference.

The current work branch is not yet a Community release. The first publishable alpha requires, at minimum:

- an independent application ID and signing certificate;
- complete removal of the closed-source Helper and Pro licensing system;
- complete removal of Firebase Analytics and Crashlytics;
- a manually triggered, signed GitHub Release workflow;
- safe allowlisted configuration export and transactional import;
- a stable floating bottom bar customizer;
- repaired CSS parsing, settings icons, and accent-color presets;
- local diagnostics and documented migration boundaries.

Do not install an APK labeled as WaEnhancer Community unless it is attached to this repository's GitHub Releases and its checksum and signing identity have been verified.

## Planned first-alpha features

The first alpha is based on the stable `1.7.0` behavior and selectively ports auditable improvements:

- privacy and message hooks already present in the stable base;
- floating bottom bar controls with safe slider normalization;
- FAB modes: `Default`, `Minimal`, and `Hidden`;
- configurable selected-tab indicator;
- `Stable Glass`, `Compact`, and `Accessibility` presets;
- semantic accent-color presets for Green, Blue, Cyan, Purple, Orange, Red, and Pink;
- CSS parser fixes, basic rollback, and safe mode;
- repaired settings icons and a distinct WaEnhancer entry icon;
- versioned configuration backup with an explicit allowlist.

`Deleted for Me`, preserved deleted media, encrypted full backup, the Element Inspector, Advanced/Liquid Glass, and the future `You` tab are later blocks with separate migration and review gates.

## Security and privacy model

The target Community architecture has these rules:

- all executed code is built from this repository;
- no external Helper APK, injected DEX, or external native library;
- no analytics or remote crash reporting;
- no token embedded in the APK;
- local diagnostics with sensitive-data redaction;
- explicit provider operations and caller validation;
- reversible preference and database migrations;
- no destructive migration fallback;
- configuration export by allowlist, never by dumping all preferences.

See [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), [MIGRATIONS.md](MIGRATIONS.md), and [ARCHITECTURE.md](ARCHITECTURE.md).

## Build and release

The release workflow is intentionally manual. It must:

1. be started with `workflow_dispatch`;
2. use JDK 17 and validate the Gradle wrapper;
3. decode the signing keystore only in the runner's temporary directory;
4. build the release variant;
5. verify the APK signature, certificate, application ID, version, and SHA-256;
6. create a GitHub Release and attach the APK directly;
7. remove temporary signing material;
8. avoid GitHub Actions artifacts.

Required repository secrets:

```text
KEYSTORE_BASE64
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

The current key alias is expected to be `batteryremapper-oneui` while the existing keystore retains that alias.

## Installation

Published releases require:

- a supported Android device;
- root and a working Xposed/LSPosed environment;
- WhatsApp or WhatsApp Business explicitly selected in the module scope;
- a supported WhatsApp version.

After installing a verified release, enable the module in LSPosed, select only the intended WhatsApp packages, force-stop WhatsApp, and reopen it.

## Compatibility

The initial device baseline is:

- Galaxy S25 Ultra;
- Android 16;
- One UI 8.5 or equivalent environment;
- WhatsApp `2.26.27.85` as the first compatibility baseline.

Compatibility with a nearby WhatsApp version is not assumed. Hook failures should disable the affected feature rather than crash the entire app when possible.

## Responsible use

Use the module only on devices and accounts you control and in accordance with applicable law, workplace rules, and platform terms. Features that alter delivery state, privacy indicators, forwarding limits, media handling, call behavior, or automated messaging can affect other people and can trigger account restrictions.

Do not use this project for harassment, spam, unauthorized surveillance, credential theft, impersonation, bypassing lawful access controls, or collecting data without consent.

## License and attribution

The project remains licensed under the GNU General Public License version 3. Copyright and attribution from the upstream project must be preserved. Distributed APKs must have corresponding source code available, and modifications must be identified clearly.

WaEnhancer Community is a modified, independent project and must not be presented as an official WhatsApp product or as an official release from the upstream maintainer.
