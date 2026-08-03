# Privacy

WaEnhancer Community is designed to operate without analytics or remote crash reporting.

## Data processed locally

Depending on the features enabled, the app or injected module may process locally:

- module preferences and visual settings;
- WhatsApp version and hook status;
- message identifiers and metadata required by enabled features;
- `Deleted for Me` records;
- preserved media selected by the user;
- Tasker automation history;
- local diagnostic events and stack traces.

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

Network access is limited to explicit functions such as checking this repository's releases, downloading an update requested by the user, or opening documentation. Network features must identify their destination and purpose. The APK must not contain a GitHub token or another private credential.

## Diagnostics

Diagnostics are local and size-limited. Reports must redact message text, phone numbers, JIDs, names, tokens, private paths, and cryptographic material by default. The user must be able to preview a diagnostic report before sharing it.

## Backups

Configuration export uses an explicit allowlist. It must not include:

- private keys, certificates, keyboxes, or tokens;
- license or Helper data;
- absolute device paths;
- caches, heartbeats, or classloader state;
- full diagnostic logs;
- internal installation identifiers.

A future full backup may include `Deleted for Me` messages and, when selected, preserved media. Portable full backups must use authenticated encryption with a user-provided password.

## Android and WhatsApp access

As an Xposed/LSPosed module, WaEnhancer Community runs inside selected WhatsApp processes and can access data visible to those processes. Users should enable the module only for supported packages and should review every feature they activate.

## Deletion

Uninstalling the app normally removes its private application data. Exported backup files and media copied outside the app's private storage remain under the user's control and must be deleted separately.
