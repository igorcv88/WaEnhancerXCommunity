# Security Policy

## Project scope

WaEnhancer Community is an independent GPLv3 fork of WaEnhancer. It is not affiliated with WhatsApp, Meta, or the upstream maintainer.

The project injects code into WhatsApp through Xposed/LSPosed. That architecture gives the module access to sensitive application state. Security reports involving data exposure, exported Android components, update verification, backup handling, signing, or code loaded into the WhatsApp process are treated as high priority.

## Supported versions

Security fixes are provided for the latest published WaEnhancer Community release and, when practical, the immediately preceding stable release. Development builds and unsupported WhatsApp versions may receive fixes without compatibility guarantees.

## Reporting a vulnerability

Do not open a public issue for a vulnerability that could expose messages, media, credentials, signing material, private backups, or an exploitable Android component.

Use GitHub's private vulnerability reporting feature when it is enabled for this repository. Include:

- affected commit or release;
- affected WhatsApp and Android versions;
- reproduction steps;
- expected and actual behavior;
- relevant logs with message text, phone numbers, JIDs, tokens, paths, and personal data removed;
- suggested remediation, when known.

Do not include private keys, keyboxes, access tokens, signing keystores, real conversations, or unredacted databases in a report.

## Trust boundaries

The following rules apply to the Community fork:

- no closed-source Helper APK is loaded into the module or WhatsApp process;
- no external DEX or native library is appended to the runtime classloader;
- no Firebase Analytics or Crashlytics collection;
- no token is embedded in the APK;
- network requests must have a documented user-visible purpose;
- release APKs must be signed, verified, checksummed, and attached directly to a GitHub Release;
- exported providers, receivers, services, and activities must be justified and validate the caller where applicable;
- backups must use an explicit allowlist and must not export secrets or internal paths;
- migrations must preserve the source until the destination has been validated.

## Storage and IPC model

Two preference files, with the rule for which one a key belongs to recorded in
`PreferenceSchema`:

- **public store** — the default preference file, deliberately world-readable so
  `XSharedPreferences` can serve the hooked WhatsApp process. It holds only keys a hook needs
  and never holds a secret.
- **private store** — `MODE_PRIVATE`, reachable only by the module's own UID. It holds user
  secrets, caches and internal state.

A secret a hook genuinely needs is served on request through a UID-validated provider call and
held only for the duration of the operation. It is never written to the world-readable file.

Migration into this split is additive: values are copied and verified before anything is
removed, `clear()` is never used as a migration step, and a snapshot is written first. Secrets
leave the public file only after the copy has been verified and a reader exists for the hooked
process.

### Caller validation

Cross-process entry points authorise by Binder calling UID resolved to installed packages, not
by a package name carried in an extra. Accepted callers are the module itself and the WhatsApp
packages it is scoped to.

- `HookProvider` (`${applicationId}.hookprovider`) validates the caller before clearing the
  calling identity, serves reads only for keys the schema marks public, accepts writes only for
  keys the schema knows, and no longer implements a clear operation at all.
- `DeletedMessagesProvider` (`${applicationId}.provider`) validates the caller on every
  operation. Its generic preference methods were removed rather than guarded.
- The quick-settings tiles are guarded by `BIND_QUICK_SETTINGS_TILE`.
- `EmbeddedSettingsActivity`, `RecordingsActivity`, `ChangelogActivity`,
  `SupportedVersionsActivity` and `BridgeService` are exported because the hooked WhatsApp
  process launches them by name.

### Automation integration

The integration is off by default. When enabled, a request to send a message must present a
per-installation token generated with a CSPRNG, kept in the private store, never exported in a
backup, and compared in constant time. Requests are rate limited and de-duplicated.

A broadcast carries no caller identity, so the package allowlist is defence in depth rather
than a boundary; **the token is the boundary**. Outgoing events are explicit intents addressed
only to allowlisted packages, and the message body is included only when the user opts in.

A legacy unauthenticated mode exists for one release to ease migration of existing automation
profiles. It is off by default and is insecure by design.

### Backups

Settings exports carry only what the schema marks exportable. Secrets, caches and internal state
are never included, and the export states which secrets it left behind so their absence is not
silent. Android cloud backup and device-to-device transfer are disabled for this application.

### Updates

A downloaded APK is verified before it reaches the installer: SHA-256 against the digest
published with the release, the package name, the signing certificate against the installed
application, and that the version is newer. A downgrade requires an explicit user action.

## Data-preservation rule

A security change must not silently destroy configuration, `Deleted for Me` records, preserved media, or compatibility with an existing installation. Critical storage changes require snapshot, validation, rollback, and a documented migration path.

## Release integrity

Official Community releases are published only through the manually triggered GitHub Actions workflow. The release body includes the APK SHA-256 checksum. A release is invalid if the APK is unsigned, debug-signed, signed by an unexpected certificate, or has an unexpected application ID or version.

## Responsible disclosure

Please allow a reasonable period for investigation and remediation before public disclosure. The project will acknowledge valid reports, document the affected versions, and credit the reporter unless anonymity is requested.
