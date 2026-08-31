# Privacy

WaEnhancer Community is designed to operate without analytics or automatic remote crash reporting.

## Data processed locally

Depending on the features enabled, the app or injected module may process locally:

- module preferences and visual settings;
- WhatsApp version, resolver results, hook status, and explicit validation-session evidence;
- message identifiers and metadata required by enabled features;
- `Deleted for Me` records and their message content;
- preserved media selected by the user;
- Tasker automation state/history;
- local diagnostic events and stack traces;
- data selected by the user for configuration or full backup.

This data remains on the device unless the user explicitly exports or shares it.

## Data not collected by the project

The Community fork must not send the following to project-controlled servers:

- message content;
- contact names, phone numbers, JIDs, or group identifiers;
- media;
- usage analytics;
- crash reports;
- device identifiers;
- preference contents;
- private backups;
- tokens, certificates, private keys, or keyboxes.

Firebase Analytics and Firebase Crashlytics are not part of the Community architecture.

## Network access

Network access is limited to explicit functions such as reading this repository's release/notice metadata, downloading an update requested by the user, or opening documentation. Network features must have a documented user-visible purpose. The APK must not contain a GitHub token or another private credential.

## Diagnostics

Diagnostics are local and size-limited. Persisted/shared diagnostic text is sanitized for message-like fields, phone numbers, JIDs, email addresses, private paths, credentials, and cryptographic material. Functional validation is started explicitly by the user and reports hook/resolver evidence rather than conversation content.

The user can preview diagnostic text before copying or sharing it. There is no automatic diagnostic upload.

## Backups

There are two distinct backup types.

Configuration backup uses an explicit schema allowlist and excludes secrets, private keys/certificates/keyboxes, internal paths, caches, heartbeats, classloader state, and diagnostic logs.

Full backup is intentionally more sensitive. When the user creates one, the current format can include:

- `Deleted for Me` messages;
- private values that `PreferenceSchema` classifies as secrets;
- preserved media when the user chooses to include it.

The full manifest is encrypted and authenticated with a user-provided password before it leaves app-private processing. Restore validates the encrypted payload before mutating preferences, databases, or private files. A full-backup file should be treated as sensitive even though it is encrypted.

Android cloud backup and device-to-device transfer are disabled for the application; portable transfer is handled by the app's explicit backup flows.

## Android and WhatsApp access

As an Xposed/LSPosed module, WaEnhancer Community runs inside selected WhatsApp processes and can access data visible to those processes. Users should enable the module only for supported packages and should review the features they activate.

## Deletion

Uninstalling the app normally removes its private application data. Exported backup files and media copied outside the app's private storage remain under the user's control and must be deleted separately.
