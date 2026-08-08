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
