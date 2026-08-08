# Migration Policy

This document defines the rules for changes to preferences, providers, databases, backups, and private files.

## Core rule

No migration may delete or overwrite the only readable copy of user data before the destination has been written and validated.

## Required sequence

A critical migration must:

1. identify the source schema and version;
2. create a local snapshot when the data can be changed destructively;
3. validate that the source is readable;
4. write the destination without deleting the source;
5. validate types, record counts, hashes, constraints, and file existence as applicable;
6. switch reads to the destination while retaining a documented fallback;
7. record the result locally without sensitive values;
8. support rollback;
9. keep the old structure for at least one stable release;
10. make cleanup explicit and optional until the migration has been proven in production.

## Preferences

Preference migration is divided into stages:

- inventory every key and its readers and writers;
- define a typed schema with defaults and bounds;
- accept compatible legacy representations such as integer, float, or string where safe;
- shadow-write old and new stores during transition;
- prefer the new value and fall back to the legacy value;
- compare effective values and record redacted mismatches;
- remove legacy reads only after upgrade tests pass;
- never move secrets into a store readable by the WhatsApp process.

The physical split between public and private stores belongs to Block C and must not be implemented during Blocks A or B.

## Configuration backups

A new backup format must be versioned and use an explicit allowlist. Import must validate the complete input before changing preferences, create a snapshot, apply changes transactionally, ignore unknown keys, migrate known legacy names, normalize safe out-of-range values, and produce an import report.

Legacy JSON must never reintroduce keyboxes, private keys, certificates, tokens, Helper paths, license state, or internal classloader data.

## Deleted for Me database

Database migrations must be incremental and transactional. They require a pre-migration snapshot when a destructive operation is unavoidable, `PRAGMA integrity_check`, record-count comparison, and rollback testing. `fallbackToDestructiveMigration` and automatic database deletion are prohibited.

The full `Deleted for Me` and preserved-media migration belongs to Block D and must begin only after the storage and IPC work in Block C has been merged and validated.

## Package migration

Because WaEnhancer Community uses a different application ID and signing key, Android will not automatically inherit data from the upstream package. Migration should prefer export from the old app and import into the Community fork. A root-assisted path may copy a consistent snapshot after controlled force-stop/checkpoint, but it must import records into the new schema rather than blindly replacing the destination database.

## Failure behavior

When validation fails, the app must keep the source intact, restore the pre-migration state when necessary, disable only the affected feature, and report a clear local error. A failed migration must not leave the app in a partially switched state.

## Block C — preference storage split (migration version 1)

### What changes

Values the hooked WhatsApp process must not be able to read move out of the world-readable
preference file into a `MODE_PRIVATE` store named `private_config`. `PreferenceSchema` records,
per key, whether it belongs to the public or the private store.

The public store is the existing default preference file rather than a newly named one. Every
hook reads it today through `XSharedPreferences`, and relocating the public keys in a single
step would change the one working cross-process path — the largest configuration-loss risk in
this project. The plan puts preservation of data above every other goal, so the file keeps its
role and gains a name.

### Sequence

Run automatically at application start, in this order:

1. `PreferenceMigration.copyPrivateValues` — writes a snapshot of the public store to
   `files/migration_snapshots/`, copies every private-store key across, then verifies the copy
   value by value. The public copy is left in place. Nothing is marked migrated unless every
   value verified.
2. The automation token is minted if absent.
3. `PreferenceMigration.removeMigratedSecrets` — removes the secrets from the world-readable
   file, and only then. It refuses unless the copy has been verified and a UID-validated reader
   exists for the hooked process, and re-checks that the private copy still matches before
   deleting anything.

`clear()` is never used at any point.

### Compatibility

**Upgrade.** Existing settings are untouched. Secrets are copied before being removed and remain
readable by the features that use them, now through the provider rather than from disk.

**Downgrade.** Step 1 is additive, so a build that only knows the public store still finds every
public setting. A build older than step 3 will not find the secrets, because they have been
removed from the file it reads; re-entering them, or restoring a snapshot, recovers them.

**Failure.** Any failure leaves the public store as it was. The migration is wrapped so that it
can never prevent the application from starting.

### Rollback

`PreferenceMigration.rollback` restores from a snapshot in `files/migration_snapshots/`, keeping
the five most recent. It writes the recorded values back rather than clearing first, so a
setting made after the snapshot is not lost. A corrupt snapshot is rejected without touching the
store.

### Backup allowlist

The exportable set is derived from `PreferenceSchema` rather than maintained by hand. The
previous hand-written list named 43 keys that do not exist and misspelled others, so most
settings were silently dropped by both export and import. Files written by that build are
handled: the drifted names are mapped back onto the real keys through `LEGACY_ALIASES`.
