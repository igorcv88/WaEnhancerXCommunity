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

## Data-preservation rule

A security change must not silently destroy configuration, `Deleted for Me` records, preserved media, or compatibility with an existing installation. Critical storage changes require snapshot, validation, rollback, and a documented migration path.

## Release integrity

Official Community releases are published only through the manually triggered GitHub Actions workflow. The release body includes the APK SHA-256 checksum. A release is invalid if the APK is unsigned, debug-signed, signed by an unexpected certificate, or has an unexpected application ID or version.

## Responsible disclosure

Please allow a reasonable period for investigation and remediation before public disclosure. The project will acknowledge valid reports, document the affected versions, and credit the reporter unless anonymity is requested.
