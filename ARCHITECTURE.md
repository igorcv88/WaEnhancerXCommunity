# Architecture

## Base and identity

WaEnhancer Community is based on the upstream `1.7.0` release commit `433a1c630bc1286f2db3c657a63f477aa0aa426d`. Later beta code is a reference source only; useful changes are ported selectively rather than merged wholesale.

The working application ID is `com.waenhancer.community`. Java package names and the Android namespace may be migrated separately when doing so does not create unnecessary compatibility risk.

## Runtime model

The project has two main execution contexts:

- the standalone Android application, which owns settings, local UI, backups, updates, and private storage;
- the injected WhatsApp or WhatsApp Business process, which loads approved hooks and reads only the configuration needed by those hooks.

Code executed in either context must be present in this repository and built into the published APK. The Community fork does not load a closed-source Helper APK, external DEX, or external native library into the runtime classloader.

## Intended data boundaries

The final design separates:

- `public_config`: non-secret hook flags and visual parameters required by the WhatsApp process;
- `private_config`: tokens, migration state, security metadata, updater internals, and other values that WhatsApp does not need to read directly;
- private databases and files: `Deleted for Me`, preserved media, local logs, and migration snapshots.

The physical split and migration protocol belong to Block C. Blocks A and B may define schemas, tests, and interfaces, but must preserve the current storage bridge until the critical migration is implemented and validated.

## IPC direction

The intended provider design is capability-specific:

- a read-only configuration bridge exposes only approved public keys to authorized WhatsApp UIDs;
- deleted-message and media operations use explicit endpoints, validated UIDs, canonical paths, sanitized identifiers, and no generic preference read/write operation;
- Tasker integration uses explicit intents, a per-installation secret, an authorized-package allowlist, rate limiting, deduplication, and local history.

Provider and Tasker hardening belongs to Block C.

## Build and release

Releases are produced only by the manually triggered GitHub Actions workflow. The workflow uses JDK 17, validates the Gradle wrapper, builds one release APK, verifies the signing certificate and manifest identity, calculates SHA-256, and attaches the APK directly to a GitHub Release. It must not run on push, pull request, tag, or schedule, and must not upload an Actions artifact.

## Work blocks

### Block A — foundation and visual work

Owned by ChatGPT App / GPT-5.6 Sol. It includes repository identity, manual releases, removal of Pro/Helper/Firebase, the floating bottom bar, CSS parser stability, settings icons, and the first semantic accent-color implementation.

It must stop before physical preference-store separation, provider protocol replacement, Tasker protocol migration, or `Deleted for Me` database migration.

### Block B — safe configuration backup v1

Owned by the same ChatGPT environment. It introduces a versioned allowlisted configuration backup and transactional legacy import while retaining the existing storage mechanism.

### Block C — critical storage and IPC

Owned by Claude Code / Opus 5. It inventories preference ownership, separates public and private storage, implements dual-read/shadow-write migration, hardens providers and Tasker, and verifies rollback.

### Block D — Deleted for Me and full backup

Owned by Codex CLI / Terra after Block C is merged. It implements database migrations, preserved media, encrypted full backup, restoration, and corruption tests. One consolidated Opus 5 audit follows the completed implementation.

### Block E — stabilization

Build, R8, manifest, compatibility, performance, signing, and device validation remain in the environment that already owns the active block.

### Block F — advanced visual features

Owned by Claude Code after data work is stable. It includes the open Advanced/Liquid Glass system, Element Inspector, and the future `You` tab.

## Non-negotiable invariants

- preserve user data and compatibility before hardening;
- do not store secrets in configuration readable by WhatsApp;
- do not load closed code into the module or target process;
- do not send analytics or crash reports;
- do not embed tokens in the APK;
- do not expose generic provider operations;
- do not apply destructive database fallback;
- do not let one agent advance into the next block without a recorded handoff and green gate.
