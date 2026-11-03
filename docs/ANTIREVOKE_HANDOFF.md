# AntiRevoke state and legacy marker handoff

Current branch: `fix/antirevoke-legacy-marker-bind`, based on `master@41de3b31`.
Previous storage implementation: merged PR https://github.com/igorcv88/WaEnhancerXCommunity/pull/136.
See `ANTIREVOKE_PERSISTENCE.md` for the full storage contract and remaining coverage limits.

The owner reports that preserved content survives the module updates tested so far.
Older preserved messages can lack their revocation marker. The supplied Inspector data
contains structural/glass evidence, not a query of message keys or durable revocation
records. Neither a missing icon nor retained audio alone establishes the historical
revocation identity or the availability of an archived original.

Two source regressions in #136 are corrected:

- Binding had stopped using an explicit `message_add_on` relationship when the current
  key did not identify the old event. The fallback is restored on a bounded worker,
  with current-account/current-row/key verification and indexed, limited queries.
  Only a unique linked durable event qualifies; ambiguous, oversized, unindexed or
  unknown relationships leave the native unmarked state unchanged.
- Binding had conflated an existing old event whose timestamp is zero with a missing
  event. `RevokedMarker`/`Match` now keep presence separate from time. Such an event
  retains the configured icon/label without displaying a made-up deletion date.

Positive/negative link results use a 256-entry identity cache; misses expire in five
seconds. At most 64 pending jobs are coalesced. Recycled/outgoing/disabled bindings change
identity before worker completions, and cleanup only removes owned decorations. Neither
source/history rows, schema, snapshot contents, encryption, the SQL protection nor player/
Liquid Glass sources are changed by this follow-up. No new device telemetry is added.

Validation: Debug/Release Java compilation passed; **655 JVM tests, zero failures/errors/
skips**; Android instrumentation sources compiled, including six new synthetic link/store
compatibility tests (18 AntiRevoke SQLite/KeyStore/link tests total). Eight host SQLite
migration/legacy-read tests passed, including presence with timestamp zero and unchanged
historical rows. Instrumentation was compiled, not executed. Diff checks passed; no
workflow dispatch, merge, private image/capture or phone database is included.

Next: normal visual acceptance on already preserved legacy voice/text rows, including
transcription, scroll/recycling and conversation reentry. This is not a repeat of basic
new-revocation testing and does not claim a recovery of unidentifiable historical
placeholders. If metadata never existed or the source has no verifiable relationship,
these changes cannot safely manufacture it. A structural capture alone does not select
between that case and a later native view redraw. Do not merge automatically.
