# AntiRevoke persistence

AntiRevoke now acknowledges a protected incoming message only after an independent SQLite
transaction commits its encrypted content snapshot, stable identity, row alias and legacy
marker. It no longer schedules the only durable write with an unobserved future. A missing
phone/JID conversion does not prevent persistence.

## Storage and migration

`delmessages.db` version 13 adds `revoked_snapshots`, `revoked_snapshot_aliases` and indexes.
It retains all existing `delmessages`, `deleted_for_me`, `deleted_media` and
`deleted_media_refs` rows. SQLiteOpenHelper's upgrade transaction runs integrity checks
before and after DDL and compares the four source-table counts. Failure rolls back; there
is no file replacement, table deletion or destructive recovery. Downgrades remain refused.
The helper owns database handles; record/cursor methods must not close them.

A snapshot's identity is the canonical physical msgstore path (package and account),
message key and chat row. Row aliases preserve subsequent observed row IDs without
replacing the first snapshot. The most recently observed alias resolves placeholder
replays; the current row's actual incoming key is checked before blocking a write. JID is
optional metadata, not a nullable uniqueness key. New snapshots cannot leak through the
old global key-only marker fallback into another account. Original v12 records have no
account field: their legacy key-only compatibility path remains, without inventing an
account assignment or changing those records.

The content envelope uses AES-GCM, a random 96-bit IV, and account/key/chat-bound
associated data. The AES key lives in the hosting UID's AndroidKeyStore and is never
exported through preferences or providers. Missing/unreadable keys never trigger data
cleanup. Only snapshots for actual attempted revocations are captured. Text/caption
(`message.text_data`), original timestamp, message type, stable row/chat/key and an optional
JID/media-path reference are retained. Text is limited to 65,536 characters and the
serialized payload to 256 KiB. Oversized/failed snapshots are reported, never silently
truncated or acknowledged as durable.

Media policy is **references only**: no media bytes are read or copied during a revoke;
known `message_media` cleanup is blocked for an already committed event while protection
is enabled. Files keep their existing WhatsApp ownership and lifetime. This is not a
separate media-file backup, and cannot restore a file removed by WhatsApp or another app.
Encrypted snapshots/aliases are retained without automatic pruning. The key and database
survive an update of the module APK and a WhatsApp process restart; clearing/uninstalling
WhatsApp or transferring its private database/key to another UID is outside this guarantee.
No new portable backup/import contract is introduced.

## Hooks and failure behavior

The platform guard hooks `SQLiteStatement.execute`, `executeUpdateDelete` and `executeInsert`.
AOSP's `SQLiteDatabase` convenience methods and `execSQL` use statements; precompiled
statements read their current bindings at execution, so rebinding and reuse are covered.
The three `SQLiteProgram` fields are resolved before registration. A partial install is
rolled back. The SQL guard is installed independently of WhatsApp/status/playback resolver
failures. Platform references:

- https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/database/sqlite/SQLiteStatement.java
- https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/database/sqlite/SQLiteProgram.java

Outside a known revoke callback, parsing deliberately supports bounded single-row
`DELETE`, placeholder `UPDATE`, `INSERT OR REPLACE`/`REPLACE` by `_id`, and placeholder
replay by committed key/chat. It accepts quoted identifiers and whitespace. It retains the
previous incoming single-row DELETE protection; it does not broaden that policy to bulk
cleanup. Ordinary text updates and other databases/tables remain native. Within an
identified callback, a thread-local barrier also covers other predicate shapes for that
account and target row. Explicit `_delpass` actions bypass that barrier until the callback
returns. Native SQLite calls that bypass Android's statement APIs, unknown unscoped bulk
SQL, and changes while the module has not loaded are not proven protected.

Known primitive results keep the correct boxed type; known store enums use `DIDNT_STORE`
by name, never a guessed ordinal. Opaque results are constructed only with a resolved,
assignable None-ack contract. An unknown return type keeps the committed snapshot and
explicitly delegates to the installed SQL barrier rather than returning an invalid object.
A missing barrier or unrecognized unscoped SQL emits a bounded feature warning. This is
not a claim that an arbitrary future WhatsApp signature or native database implementation
has been validated.

An identified callback arms its account/row/key barrier before any snapshot size check,
key access or database write. A failed checkpoint leaves that barrier armed until the
callback returns; message writes in that scope do not require another archive lookup to
succeed. Scoped media cleanup is also retained while the checkpoint is unavailable.

On snapshot failure, method hooks do not report successful persistence. A matched SQL
write still leaves the original native row intact as emergency retention and records
`snapshot-not-committed`; that emergency path has no durability acknowledgement. There is
no way to guarantee a successful durable write when storage/key access is failing.
Warnings contain reason/class only, never SQL, bind values, message text, contacts or paths.
Initialization/key loading runs in FeatureLoader's worker; the synchronous critical path
is a bounded indexed row snapshot, encryption and commit, with no media copy or history
scan. Its actual latency still needs device validation.

## Binding and recovery

Bindings read account/row/key timestamps through bounded caches; new commits invalidate
negative misses. Legacy lookups are indexed and cached. There is no database scan per draw
or scrolling callback. A known native placeholder can display its archived text on a
bounded worker, checking the current binding identity before publishing a completion.
Normal retained rows keep their native content and layout. An archive is not sufficient
to fabricate an unrelated placeholder's missing original identity or reconstruct a fully
removed native message/media row. No recovery of previously lost historical text is claimed.

## Validation

Host tests: `tools/test_antirevoke_migration.py` executes production DDL using real SQLite,
checks v12 records/references, rollback, identity separation, aliases, query plans, and a
committed event followed by immediate subprocess termination. JVM tests exercise the SQL
parser, typed result/None-ack policy and failed-checkpoint callback barriers. Android instrumentation tests in
`RevocationArchiveDeviceTest` use isolated synthetic databases and AndroidKeyStore to cover
commit/reopen, negative misses, encryption/authentication, account isolation, replay,
row aliases, missing JID, group/LID, text/caption, size rejection, migration and read-only
legacy access after repeated reopen without a new revocation.

Host checks and instrumentation compilation do not validate LSPosed hooking, obfuscated
WhatsApp resolvers, APK-update behavior or scroll/input/player latency on a phone. Those
remain the device acceptance boundary; no new diagnostic collection is required here.

For APK-update acceptance, start with an already preserved message and its existing marker.
Reopen that same message after the module APK update and a WhatsApp process restart, then
check content/marker across scrolling and conversation reentry. This is independent of the
already demonstrated interception of a newly revoked message. Recovery of older, already
unidentifiable placeholders requires an earlier source containing the original content;
this implementation does not invent that association or replace the active database.

## Legacy marker compatibility follow-up

Marker presence is separate from deletion time: a legacy row with timestamp zero is a
known revocation with an unknown date. Its icon/label remains available, without an
invented date, underline or date-click action. No historical row is rewritten to assign
it a new timestamp.

The v13 binding refactor had also omitted the prior `message_add_on` key lookup. When
current-key reads have no event, a bounded worker may consult explicit add-on links for
the current incoming row. It verifies the physical account path and the row's current key,
requires an indexed parent lookup, and refuses results exceeding eight rows. Only one
unambiguous linked key with a durable legacy or same-account event qualifies. Preserved
content, audio type, a similar time, or the existence of an add-on alone never invents a
revocation. This path reads metadata only; it writes neither WhatsApp nor archive rows.

Linked marker results are cached for 256 account/row/current-key identities. Negative
results expire after five seconds; work is coalesced with at most 64 pending identities
on the existing bounded worker. New bindings, including outgoing/disabled recycled views,
replace their identity before an asynchronous result may publish. Cleanup removes only
owned decoration and does not overwrite a newly bound native date with an old label.
The SQL protection, durable checkpoint and cryptography paths are unchanged.

These source fixes restore two previous compatibility behaviors. A structural Inspector
capture cannot prove that a particular old message still has its revocation record/link,
or that WhatsApp has not subsequently changed its views. Actual visual acceptance remains
separate from host tests; missing historical metadata is not reconstructed from content.
