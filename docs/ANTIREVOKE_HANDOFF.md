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

Validation: Debug and Release Java compilation passed; 622 JVM tests passed with zero
failures/errors/skips; Android instrumentation sources compiled, including 12 synthetic
SQLite/AndroidKeyStore tests. Seven host SQLite migration/durability/identity tests passed,
as did existing message-history migration, app-only runtime/IPC, bubble-lifecycle and diff
checks. Android test-rules was added solely for existing UiThreadTest annotations.
Instrumentation was not executed. No device/LSPosed/WhatsApp signature, APK-update or UI
latency acceptance is claimed. No workflow was dispatched and no merge was performed.

Review follow-up: the callback barrier is now armed before snapshot size checks,
encryption or commit. Six JVM regressions execute the production checkpoint/context with
size/key/storage failures, assert that key-based SQL fallback and media retention still
have the correct account/row identity, reject false acknowledgements, and check nested
callbacks, explicit pass, cleanup and thread isolation. Matching message writes do not
perform an additional archive-state query that could reopen the same storage failure.
Placeholder INSERT/REPLACE values may change key_id: the actual overwritten row or an
identified callback still names the original, while an alias lookup must reject an unrelated
reused row. Three additional SQL-policy tests cover those distinctions.
The legacy SQLite test executes the production compatibility query after migration and
reopen without modifying source rows or generating a new revocation. The additional
Android test covers legacy helper reads across repeated reopen; it is compiled, not run.

Next: validate the module APK update with an already preserved message as the baseline.
Compare that same message's original content and marker after the update, WhatsApp restart,
scrolling and conversation reentry. Do not repeat basic new-revocation acceptance as proof
of update persistence. Historical placeholders require a separate earlier content source;
no recovery or association is fabricated. Keep runtime compatibility and measured UI
latency separate from host test results. Do not merge without explicit instruction.
