# Migration Policy

This document defines the rules for changes to preferences, providers, databases, backups, and private files.

## Core rule

No migration may delete or overwrite the only readable copy of user data before the destination has been written and validated.

## Required sequence

A critical migration must:

1. identify the source schema and version;
2. create a local snapshot when data can be changed destructively;
3. validate that the source is readable;
4. write the destination without deleting the source;
5. validate types, record counts, hashes, constraints, and file existence as applicable;
6. switch reads only after validation, retaining a documented compatibility path when needed;
7. record the result locally without sensitive values;
8. support rollback where the old representation remains meaningful;
9. keep obsolete source data until the migration has been exercised successfully;
10. make cleanup an explicit later action rather than part of first-read migration.

`clear()` is not an acceptable migration strategy.

## Preference schema and stores

`PreferenceSchema` is the source of truth for preference type, bounds, sensitivity, exportability, and target store.

The implemented storage split uses:

- the default/public store for non-secret values required by hooks inside WhatsApp;
- `private_config` (`MODE_PRIVATE`) for secrets and module-only state.

The storage migration copies private values first, verifies the copy, and only then removes migrated secrets from the public store. Migration snapshots are kept under the app's private files directory. A failed copy must leave the public source untouched.

Preference changes must remain tolerant of safe legacy representations (for example numeric values stored as strings) when conversion is unambiguous. Unknown or invalid values fall back safely rather than crashing the hooked process.

## Configuration backup

Configuration backups are versioned and use the exportable set defined by `PreferenceSchema`. Import must validate the complete input before changing preferences, create a snapshot, apply changes transactionally, ignore unknown keys, migrate recognized legacy aliases, normalize safe out-of-range values, and produce an import report.

Configuration export must not contain private keys, certificates, keyboxes, tokens, internal paths, caches, license/Helper remnants, or classloader/runtime state.

## Deleted for Me and preserved media

Database migrations are incremental and transactional. A destructive operation requires a snapshot and integrity validation; `fallbackToDestructiveMigration` and automatic database deletion are prohibited.

Preserved-media migrations must verify storage IDs, paths, hashes, sizes, quotas, and database/file consistency. A database row may not claim a preserved file exists until the file has been successfully written and validated.

## Full backup and restore

Portable full backup is a separate format from configuration backup. The current format contains `Deleted for Me` records, schema-defined private secrets, and optional preserved media, then encrypts/authenticates the complete manifest with a user-provided password.

Restore must decrypt and validate the full payload before changing preferences, rows, or private files. Database changes are transactional; preference state is snapshotted in memory for rollback; newly created media is removed if restore fails. Restore must never delete the user's source backup.

## Package migration

Because WaEnhancer Community has a different application ID/signing identity from upstream builds, Android does not automatically inherit upstream app-private data. Prefer explicit export from the old app and import into Community.

A root-assisted migration, if implemented, must copy a consistent snapshot after controlled process shutdown/checkpoint and import records into the current schema. Blind replacement of destination databases/preferences is not acceptable.

## Failure behavior

When validation fails, keep the source intact, restore the pre-migration state when necessary, disable only the affected feature, and record a clear local error. A failed migration must not leave the app in a partially switched state or make application startup depend on successful cleanup.
