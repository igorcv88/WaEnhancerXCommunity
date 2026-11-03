# AntiRevoke implementation handoff

Review-ready PR: https://github.com/igorcv88/WaEnhancerXCommunity/pull/136
Branch: `fix/antirevoke-durable-snapshots`, based on `master@8cc6c517`.

The implementation commits encrypted text/caption snapshots, stable account/key/chat
identity, row aliases and legacy markers before acknowledging protection. Version 13 is
additive and verifies integrity/counts while preserving v12 history and media references.
The SQLiteStatement barrier is installed independently of optional WhatsApp resolvers.
Typed result handling delegates unknown signatures explicitly to that barrier. Indexed
bounded caches invalidate misses on commit; asynchronous placeholder-text recovery checks
binding identity before publishing. Existing notifications remain independent of whether
protection is enabled. Player and Liquid Glass sources are unchanged.

See `ANTIREVOKE_PERSISTENCE.md` for the storage contract, supported SQL shapes, failure
behavior, limits and test commands. Media preservation is references only; files are not
copied. Unknown native writes and unidentifiable/missing historical rows are not claimed
recoverable. A failed durable write is reported, while matched SQL retains the original
row as emergency retention without a successful persistence acknowledgement.

Validation: Debug and Release Java compilation passed; 613 JVM tests passed with zero
failures/errors/skips; Android instrumentation sources compiled, including 11 new synthetic
SQLite/AndroidKeyStore tests. Six host SQLite migration/durability/identity tests passed,
as did existing message-history migration, app-only runtime/IPC, bubble-lifecycle and diff
checks. Android test-rules was added solely for existing UiThreadTest annotations.
Instrumentation was not executed. No device/LSPosed/WhatsApp signature, APK-update or UI
latency acceptance is claimed. No workflow was dispatched and no merge was performed.

Next: complete the established functional device acceptance for newly revoked text,
reply/media, restart, module APK update and recycled/scrolling markers. Keep runtime
compatibility and measured UI latency separate from host test results. Do not merge
without explicit instruction.
