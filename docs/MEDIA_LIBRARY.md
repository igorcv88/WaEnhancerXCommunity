# Persistent local media library

Status: implemented, statically and JVM-validated. **Not yet validated on a physical device.**
See [Device validation still required](#device-validation-still-required).

## Why it exists

The 2026-11 audit of Unlimited View Once (`ViewOnce.java`) and Download View Once
(`DownloadViewOnce.java`) showed that neither keeps an independent copy: recent media stays
readable only while WhatsApp still has its own file (offline and after force-stop), an old
message keeps its bubble but WhatsApp shows its native "Photo expired" dialog, and Download View
Once only exports a file when the user taps it. No fixed expiry period was proven.

The media library gives the user a private, persistent copy of **their own media and of
conventional media they explicitly choose to keep**, independent of WhatsApp's cache and of
whether the original is still downloadable.

## Scope and non-goals

| Allowed (explicit user action only) | Basis recorded |
| --- | --- |
| Photos/videos the user sent (`fromMe`), including the user's own view-once | `own_media` |
| Conventional received photos/videos | `user_explicit` |
| Items restored from the user's library backup | original basis kept |

| Received view-once (42/43), **only with the owner switch "Keep received view-once"** (`media_library_keep_view_once`, **on by default** once `media_library` is on), kept automatically | `auto_view_once` |

Owner decisions 2026-11-26: the goal is a definitive copy that behaves like normal received
media; the original brief's rules about preserving ephemeral behaviour no longer apply. The
switch "Keep received view-once" therefore defaults to **on** once the library is enabled (it can
still be turned off). It is read by the module provider from its own preferences; a caller cannot
set it. What causes WhatsApp's "Photo expired" is **not** established — see
[MEDIA_LIFECYCLE_EVIDENCE.md](MEDIA_LIFECYCLE_EVIDENCE.md); the library does not depend on it,
but it can only keep media WhatsApp still has when the copy is made.

Always refused: other people's status posts; automatic capture of anything other than received
view-once (normal media needs an explicit tap).

Never done: changing a message's type or any row in `msgstore.db`, neutralising expiry,
publishing to the public gallery/MediaStore, using the cache directory as storage, sending
content to any external service, Android cloud backup/device transfer of the library
(`data_extraction_rules.xml` excludes `file` and `database` domains).

## Components

```
WhatsApp process                              Module process (com.waenhancer.community)
────────────────                              ──────────────────────────────────────────
MediaLibraryHook                              MediaLibraryProvider  (exported, UID-checked)
 ├ media viewer menu "Keep in library" ──FD──▶  import / lookup / gallery_token / openFile(r)
 ├ conversation menu "Media library" ─token─▶ MediaLibraryActivity (ChatMediaGallery alias)
 └ row overlay (ViewOverlay) ◀──openFile(r)── LibraryViewerActivity
                                              MediaLibraryRuntime → library.core.*
```

`library/core` is pure Java with no Android imports (`org.json` is platform-provided on
Android and a test dependency on the JVM):

| Class | Responsibility |
| --- | --- |
| `Sql` | Minimal SQL interface. `AndroidSql` (platform SQLite, WAL, FK on) on device; `JdbcSql` (xerial sqlite-jdbc) in tests — the same SQL runs in both. |
| `LibrarySchema` | Versioned migrations (`PRAGMA user_version`), one transaction per step, integrity check before/after, row counts may only grow, newer schema refused untouched. |
| `BlobStore` | Content-addressed files `files/media_library/blobs/<aa>/<sha256>`; staging in `staging/*.part`; SHA-256 + size computed while streaming; `fsync`; atomic rename; directory sync (best effort); free-space reserve (512 MiB on device). |
| `LibraryLock` | In-process `ReentrantLock` + `FileLock` on `media_library/.lock` (cross-process). |
| `MediaLibrary` | Import, lookup, listing, quota, trash/restore/purge, reconciliation. |
| `MediaSniffer` | Type from content (JPEG, PNG, GIF, WebP, HEIC/AVIF, MP4/3GP/QuickTime, WebM). The caller's MIME is ignored. |
| `ImportPolicy` | Authorisation rules above. |
| `ImportRequest` | Identity + bounded, validated metadata. |
| `StreamingAead` | Chunked AES-256-GCM (STREAM construction, 1 MiB chunks, PBKDF2-HMAC-SHA256 600k). |
| `LibraryBackup` | Streaming export; two-phase restore (stage + verify everything, then commit under lock). |
| `InlinePresentation` | When to present a kept copy and which native view hosts it. |

Android-only: `library/android/{AndroidSql, MediaLibraryRuntime, GalleryAccess}`,
`provider/MediaLibraryProvider`, `library/ui/{MediaLibraryActivity, LibraryViewerActivity,
LibraryGridAdapter}`, `xposed/features/media/MediaLibraryHook`.

## Storage design and the vault limitations it avoids

The catalog is a **separate database** (`databases/media_library.db`) and a separate directory
from `delmessages.db` / `files/deleted_media`; neither can migrate or delete the other's data.
`DeletedMediaVault` itself is unchanged (its `FullBackupManager` format depends on it).

| `DeletedMediaVault` limitation | Media library behaviour |
| --- | --- |
| Quota checked before deduplication | Bytes are staged, hashed, then quota is charged only if no verified blob with that hash exists (`quotaIsEvaluatedAfterDeduplication`). |
| Existing file reused without re-verification | `BlobStore.commit` re-hashes an existing blob; a damaged one is replaced by the fresh staged bytes (`corruptedExistingBlobIsNotReusedButRepairedFromFreshBytes`). |
| File/SQLite inconsistency after interruption | Order is stage → sync → rename → transaction. A failed transaction deletes a newly created, unreferenced blob; `reconcile()` (run once per process) deletes staging files and row-less blobs and marks rows whose file is gone/damaged as `missing`/`corrupt` without deleting them; re-import or restore repairs in place. |
| No cross-process concurrency guarantee | `LibraryLock` = thread lock + `FileLock`; SQLite WAL with busy timeout. Proven with a second OS process (`twoOperatingSystemProcessesShareOneLibraryWithoutCorruption`). |
| Backups as in-memory byte arrays | `StreamingAead` + `LibraryBackup` stream through fixed buffers; a 160 MiB video exports and restores in a JVM limited to `-Xmx48m`. |
| 512 MiB limit | Default 16 GiB, user-selectable 1–256 GiB (bounds 64 MiB–1 TiB), always subject to the free-space reserve; per-item limit 4 GiB. |

Identity: `(account, chat, message key, from_me)` with `UNIQUE(account_id, chat_jid,
message_key, from_me)`. The chat may be addressed by phone JID or LID; both are stored and either
matches. Re-import of a present, verified item returns `ALREADY_PRESENT` without reading the
stream. Identical bytes on different messages → one blob, several items; a blob is deleted only
when no item (including trashed ones) references it.

Account isolation: `accounts(package_name, own_jid)`. The package comes from the provider's
view of the calling UID (`CallerAuthority`), never from extras; `own_jid` is WhatsApp's own
phone JID for the active account (`WppCore.getMyUserJid`), and every operation fails closed when
it cannot be resolved. Every provider operation carries the account: `import`, `lookup` and
`gallery_token` require `own_jid`; `openFile` requires `?account=` and serves an item only when
package **and** account match (`AccessPolicy.mayRead`, tested). Limit: several accounts inside one
WhatsApp package share a UID, so Android cannot authenticate which account is active; the account
check prevents mix-ups (another account's media shown or opened after a switch, stale caches), not
a deliberately lying WhatsApp process, which can already read everything under that UID.

Inline binding and caches (`InlineBinder`, used by the hook): every bind of a conversation row,
including a bind to a message without media, issues a new token; work started for an older token
is dropped before the lookup, after the lookup and again on the main thread before the row is
touched. Lookup answers are keyed by package + account + chat + message and are valid only for
the provider's state token = library generation (`MediaLibrary.generation()`, persisted, bumped by
every import that changes content, trash, restore, purge, repair, reconcile state change and
backup restore) + the presentation settings. Thumbnails are keyed by package + account + item +
SHA-256, so a repaired item never shows its old picture. An account or WhatsApp-flavour change
clears the answer cache. Hook-side settings (`media_library*`) are read when WhatsApp starts, as for
every feature: a change applies after restarting WhatsApp; the provider-side switch applies
immediately through the state token.

Controlled deletion: visible items can only be moved to trash; trash is restorable; `purge`
refuses non-trashed items; trash older than 30 days is purged by maintenance.

## IPC contract (`content://com.waenhancer.community.medialibrary`)

| Operation | Caller | Input | Output |
| --- | --- | --- | --- |
| `call("import")` | WhatsApp/W4B UID | read-only `ParcelFileDescriptor` `media` opened by WhatsApp + identity/flags | `status` (`CREATED`, `DEDUPLICATED`, `ALREADY_PRESENT`, `REPAIRED`, `RESTORED_FROM_TRASH`, `ERROR`) + `uuid` or `error` code |
| `call("lookup")` | WhatsApp/W4B UID | ≤ 64 message keys of one chat | `uuids[]`, `kinds[]` (only available items) |
| `call("gallery_token")` | WhatsApp/W4B UID | own/chat JIDs, title, optional `open_uuid` | single-use token, 60 s TTL |
| `openFile(item/<uuid>, "r")` | module or owning flavour | — | read-only descriptor |

No query/insert/update/delete, no paths, no enumeration. The module needs no storage permission:
it never opens WhatsApp's files itself.

## Acceptance criteria (independent review, 2026-11-26)

| Criterion | Implementation | Evidence |
| --- | --- | --- |
| Complete validation of imported photos/videos | `MediaValidator` walks the whole file: JPEG markers to SOS + final EOI; PNG IHDR, every chunk CRC-32, IEND last; GIF trailer; WebP RIFF size; ISO-BMFF (MP4/3GP/MOV/HEIC/AVIF) boxes tiling the file exactly with `ftyp` first, `moov`/`moof` + `mdat` for video, `meta` for still images; WebM EBML header + Segment size. Then a device decode probe (`AndroidDecodeProbe`: bounds decode / video track + duration). Anything incomplete is refused with `INCOMPLETE_MEDIA` and nothing is stored; backups containing such a file are rejected. | AcceptanceCriteria › truncatedOrInconsistent…, validatorAccepts…, deviceDecodeProbe…, backupContainingAnIncompleteFile… |
| Periodic SHA-256 verification | `LibraryIntegrity.verifyBatch`: least recently verified first, 256 MiB per run, at every module start and every 6 h while it runs; updates state and time per file. "Verify files" re-hashes everything on demand. | AcceptanceCriteria › periodicVerificationRotates… (bounded passes cover all files, same-size damage found) |
| Explicit detection of ambiguous associations | Identity = account + chat (incl. learned aliases) + message key + direction. A lookup or import that matches more than one visible item is **never** resolved by a guess: lookups return nothing, imports fail with `AMBIGUOUS`, the report lists the group. Identical-content groups can be resolved (extra rows to trash, file kept); different content waits for the user's choice. | AcceptanceCriteria › sameMessageStoredUnderPhoneAndLid…, ambiguityWithDifferentContent… |
| Report of files without records and records without files | `LibraryIntegrity.inspect` (read-only): files without record, quarantined files, records without file, damaged files, unused records, ambiguous groups, unknown origins, staging leftovers. Maintenance **quarantines** files without a record instead of deleting them; quarantine is deleted only on an explicit, confirmed user action; keeping the same bytes again re-adopts them. | AcceptanceCriteria › reportListsFilesWithoutRecords… |
| JID/LID change, WhatsApp update, backup restore | Schema 4 `chat_aliases` (phone JID ↔ LID per account, both directions), learned from every request that carries both forms and seeded from recorded pairs; WhatsApp's `jid_map` supplies the pair in the database sweep. | AcceptanceCriteria › whatsAppUpdateThatMovesChats…, schemaUpgradeSeedsAliases…, jointBackupRestores…; InitialImport reset case |
| Joint backup of files and catalog | The encrypted `.waexlib` holds every file and the whole catalog (items, blobs, accounts, aliases, storage limit, view-once flags, origin certainty, trash state). Restored catalogs are compared field by field with the source (`LibraryIntegrity.canonicalDump`). The raw SQLite file is not copied: a logical catalog stays restorable across schema versions. | AcceptanceCriteria › jointBackupRestoresFilesAndTheWholeCatalogIdentically |
| First-run import ("optimisation on first start") | `InitialImport`: for each account, the first run imports **every** view-once photo/video (received and own) whose file WhatsApp still has, oldest first, in pages of 100, read-only from `msgstore.db`; resumable (watermark advances only over settled messages; a transient failure stops and retries next run); afterwards only newer messages, every 10 min while WhatsApp runs; resets when WhatsApp's database was recreated. Progress per account is stored in the library (`sweep_state`). | AcceptanceCriteria › firstRunImportsEveryAvailableViewOnce… |

Message ↔ media association, in order of reliability: WhatsApp's protocol message id (`key_id`,
stable across updates and reinstalls) + direction + chat; the chat is matched through all known
aliases; the account and WhatsApp flavour scope everything. WhatsApp's internal row ids are used
only as the import watermark, never as identity. What still needs the phone: whether
`jid_map`/`message_media.file_path` exist and point to view-once files on 2.26.38.74 (the sweep
falls back to a query without `jid_map`).

## Interface

Automatic view-once keep (switch on) has three triggers, all copying while WhatsApp still has the
file: a chat row is bound; the view-once viewer opens; and a read-only sweep of the newest 300
received view-once rows in WhatsApp's `msgstore.db` (`message` ⋈ `message_media` ⋈ `chat` ⋈ `jid`)
20 s after WhatsApp starts, which covers chats the user does not open. Received view-once is never
written back into WhatsApp's storage. A view-once row (received or own) with a kept copy shows the
copy **whether or not WhatsApp still has the file** (`InlinePresentation.presentKeptCopy`; normal
media keeps native-first): the view-once body (`view_once_media_container*`, WhatsApp's "1 Photo"
pill) is resized to a media bubble (`mediaBubbleSize`: 260dp wide capped at 3/4 of the row,
height by aspect ratio clamped to 140–340dp), its children are hidden, and the copy is drawn over
it center-cropped with 8dp anti-aliased rounded corners and no badge. A tap opens the copy, never
WhatsApp's view-once viewer (which would consume the message) or the "Photo expired" dialog (the
original click listener is saved and restored on rebind; if it cannot be read, nothing is
replaced). On every rebind the size, child visibility, overlay and layout listener are undone,
each only if it is still the value this module set (a value WhatsApp's own bind wrote wins).

Restore into WhatsApp (normal media) — **separate switch `media_library_restore_to_whatsapp`, on by
default (owner decision), can be turned off**: when WhatsApp's file for a message is gone and the library holds a copy,
`WhatsAppRestore` writes it to the exact path WhatsApp still records (unique temporary name, then
a hard link — atomic and never replacing — or, where the file system has no hard links, an
exclusive `O_CREAT|O_EXCL` create of the target plus a local copy; `File.renameTo`, which replaces
silently on Linux, is not used; a journal in WhatsApp's `files/waex/restore-journal` lets the next
start remove a temporary file or a partial target left by a killed process, keeping a complete
one), only
inside WhatsApp's own media roots, never over an existing file, never for view-once, never when a
deliberate user deletion is known (no reliable signal exists on 2.26.38.74, so the hook cannot
tell yet; the owner chose on by default). `msgstore.db` is not modified. Risks accepted by leaving it on: Android's media scanner indexes `Android/media`, so the file may reappear in other
gallery apps; WhatsApp's storage manager may count or delete it again; a deletion the user made in
WhatsApp or a file manager can be undone; cloud/WhatsApp backups may include it. Whether WhatsApp
itself then treats the message as downloaded, and lists it in "Media, links and docs", is
**unproven** (device check 11). When off or refused, the overlay is used.

View-once tab in WhatsApp's media screen: `ViewOnceGalleryTab` adds a "View once" chip to
`com.whatsapp.gallery.ui.MediaGalleryActivity` (`chip_layout`, IDs from the owner's 2026-11-26
inspector trace) only when the contact has kept view-once items. It shows them in a grid drawn
inside `media_hub_fragment_container`, separate from WhatsApp's Photos/Videos. The chip is a plain
`TextView` styled like the WDS chips. The native chips are Material `Chip`s: a 48dp touch target
with a smaller pill drawn inset by a `ChipDrawable` whose fill, outline and text colours the view
does not report (copying them gave a grey, oversized pill on the 36b6593/df361a3 builds). One
unchecked and, if present, one checked native chip are drawn into bitmaps and read by `ChipLook`
(pure, JVM-tested): visible pill box, fill, outline colour/width, content colour, content padding.
Ours keeps the native view height with the pill inset the same way, uses the checked chip's look
when selected, WhatsApp's `ic_viewonce` at 18dp tinted like the text; it stays outside
`WDSChipGroup`'s checked-state logic and consumes its own tap; tapping a native
chip hides it; tapping an item opens it in the module viewer. WhatsApp's lists and database are
untouched, so view-once copies never mix into WhatsApp's own Photos/Videos. The contact comes from
a chat JID in the activity's intent, else from the open conversation; otherwise no chip.

Saving to the phone gallery (explicit only): module library → long-press → "Save to gallery"
writes to `DCIM/WhatsApp View Once` for view-once copies and `DCIM/WhatsApp` for normal media
(`GalleryAlbums`, MediaStore, pending until complete). WhatsApp's Download View Once button now
also saves to `DCIM/WhatsApp View Once` on Android 10+ (previous download folder as fallback).
Catalog schema 2 adds `items.view_once`; schema 3 adds `items.view_once_known`. Rows from schema 1
are certain only for `auto_view_once` (received view-once) and `user_explicit` (received normal
media); the user's own media and backup rows are marked unknown instead of guessed and are settled
by WhatsApp (`confirm_origin`, sent when an own media row is shown); unknown rows count as normal
until then. Gallery exports go through `PendingPublish`: pending entry → write → close → publish,
and a publish that does not update exactly one entry is a failure; the pending entry is deleted on
any failure.

- **Media viewer → ⋮ → Keep in library** (the menu that already hosts Download View Once).
- **Conversation → ⋮ → Media library**: opens this conversation's private gallery (module
  activity, token-gated alias `ChatMediaGallery`). Tap opens the viewer (pinch/double-tap zoom,
  swipe between items; videos stream from the provider). Long-press: move to trash / restore /
  delete permanently.
- **Inline**: when WhatsApp no longer has a message's file and the library holds an intact copy,
  the copy is drawn with a "Library copy" badge as a `ViewOverlay` (view-once rows: media bubble
  without badge, also while WhatsApp still has the file; see above). The host does not depend on a
  native thumbnail (`InlinePresentation.selectHost`): native thumbnail → view-once bubble body →
  known media frame; otherwise the row stays native (the whole bubble is never covered). A tap on
  the host's clickable ancestor opens the copy (original listener saved and restored on rebind);
  where the host's parent is a `FrameLayout`, a ▶ target is also added, and its long-press is
  forwarded to the row so native selection still works. Everything is removed on every rebind.
- **Module settings → Media → Open media library**: all accounts, usage/quota, storage limit,
  Verify files (deep hash check), trash, Export encrypted backup / Restore backup (SAF).

Liquid Glass (Text Bubbles): the overlay is drawn by the thumbnail view's own canvas, not the row
canvas that `NativeBubbleGlass` hosts, and no bubble drawable, row background, `ColorDrawable`
fill, text colour or `DRAW_WATCH` id is touched. Row layout, `clipToPadding`, recycling and
touch dispatch are unchanged except for the optional ▶ child inside an existing `FrameLayout`.
This compatibility is argued from code, **not demonstrated on device**.

### Native gallery integration (review item 7)

Investigated without modifying WhatsApp's database:

- **Normal media — implemented:** "Restore into WhatsApp" writes the kept bytes back to the path
  WhatsApp still records for the message (only inside WhatsApp's media roots, never overwriting,
  temp file + rename). WhatsApp's own row, viewer and "Media, links and docs" read `msgstore.db`,
  which already lists the message, so they should use the file again. Unverified on device.
- **View-once — not possible without changing WhatsApp's data:** view-once messages keep types
  42/43 in `msgstore.db`; WhatsApp's gallery queries and view-once UI are driven by that type and
  by view-once state columns. Making them appear as normal media natively would require either
  rewriting those rows (forbidden) or an in-memory type override on WhatsApp's message objects,
  which risks WhatsApp persisting the altered object back to `msgstore.db` on its next update of
  that message. Not implemented; it needs a device trace of how 2.26.38.74 persists those objects
  before it can be considered. The kept copy is shown in the bubble and in the module's gallery.

Earlier analysis:

Superseded on 2026-11-26 by "Restore into WhatsApp" above for normal media. The analysis below
still applies to view-once, which is not written back and therefore does not appear in WhatsApp's
own media list.

WhatsApp's per-contact "Media, links and docs" is fed by its own `msgstore.db` queries and media
loaders. Injecting library items there would require either writing rows into `msgstore.db`
(forbidden: it would permanently change messages) or hooking obfuscated, version-specific cursor
and adapter classes whose behaviour (selection, forwarding, deletion of a non-existent message)
cannot be demonstrated without device traces. Compatibility could not be shown, so the library
ships its own gallery reachable from the conversation menu, as specified for that case.

## Backup

`.waexlib` = `WAEXLIB1 ‖ version ‖ iterations ‖ chunk ‖ salt ‖ nonce prefix` header, then
STREAM chunks. Plaintext records: `H` header, `C` catalog, one `B` per distinct blob followed by
its bytes, `E` end. Restore is two-phase: phase 1 authenticates the whole stream and stages every
blob, checking hash, size and sniffed type against the catalog — a wrong password, any modified
byte, reordering or truncation (including at a chunk boundary) changes nothing. Phase 2, under the
lock: quota after deduplication, blob moves, one transaction; existing identities are kept
(merge, never overwrite). Library backups are separate from the existing full backup format,
which is unchanged.

## Test matrix (JVM, real SQLite via sqlite-jdbc, real files)

Run: `./gradlew :app:testWhatsappDebugUnitTest`. Result on 2026-11-26 in this branch:
see the latest numbers in `handoff.md` and the PR; all library suites pass.
Mutation check: making quota ignore deduplication, or removing the import lock, each made the
expected test fail (2 failures), confirming the tests discriminate.

| Area | Test (class › method) | Result |
| --- | --- | --- |
| Photos & videos, exact bytes, content-derived type | Import › photoAndVideoAreStoredWithContentDerivedTypeAndExactBytes | pass |
| Non-media refused, nothing left | Import › mimeHintIsIgnoredAndNonMediaIsRefusedWithoutLeavingFiles | pass |
| Identical files, different messages (photo + video) | Import › identicalBytesOnDifferentMessagesShareOneBlobAndCostQuotaOnce | pass |
| Idempotent import (stream not opened) | Import › reimportingTheSameMessageIsIdempotentAndDoesNotReadTheStream | pass |
| 40 consecutive files, ordering, integrity | Import › consecutiveImportsKeepEveryFileDistinctAndOrderedByMessageTime | pass |
| Quota after dedup (64 MiB streamed) | Import › quotaIsEvaluatedAfterDeduplication | pass |
| Insufficient device storage | Import › insufficientDeviceStorageIsRefusedAndLeavesNothingBehind | pass |
| Authorisation policy (received view-once/status refused unread) | Import › receivedViewOnceAndStatusKeepNativeExpiryButOwnViewOnceCanBeKept; AeadPolicy › policyAllows… | pass |
| Multiple accounts / WA vs W4B isolation | Import › accountsAndWhatsAppFlavoursAreIsolated | pass |
| LID/phone JID identity | Import › phoneJidAndLidAddressTheSameConversationDeterministically | pass |
| Corrupt blob not reused | Import › corruptedExistingBlobIsNotReusedButRepairedFromFreshBytes | pass |
| Missing blob repaired in place | Import › reimportRepairsAMissingBlobInPlaceKeepingTheItemIdentity | pass |
| Controlled deletion, shared bytes | Import › controlledDeletionUsesTrashAndKeepsSharedBytes, expiredTrashIsPurgedOnlyAfterRetention, reimportOfATrashedMessage… | pass |
| Metadata validation before reading | Import › invalidMetadataIsRefusedBeforeReadingBytes | pass |
| Persistence across restart | Import › catalogAndFilesPersistAcrossARestart | pass |
| Interruption mid-stream | Recovery › streamFailureMidwayLeavesNoBlobNoRowAndNoStagingFile | pass |
| Interruption after rename, before commit | Recovery › catalogFailureAfterTheBlobWasMovedRemovesTheUnreferencedBlob, catalogFailureNeverDeletesBytesOwnedByAnotherItem | pass |
| Crash leftovers (staging, orphan blob) | Recovery › reconcileRemovesStagingFilesAndOrphanBlobsFromACrash | pass |
| Same-size corruption / truncation / recovery | Recovery › deepReconcile…, truncatedBlob…, unreferencedBlobRowsAreCollected | pass |
| Concurrency: 8 threads × separate connections | Concurrency › concurrentThreads… | pass |
| Concurrency: second OS process | Concurrency › twoOperatingSystemProcesses… (overlap is likely, not forced) | pass |
| Lock semantics | Concurrency › lockIsReentrantForItsOwnerAndExclusiveForOthers | pass |
| Migration: create/reopen/newer refused/failing step rollback/destructive step refused/additive step | Schema › 6 tests | pass |
| Backup round trip (photos, videos, duplicates, trash, two flavours) | Backup › roundTrip…, restoringTwiceMerges…, restoreKeepsLocalItems… | pass |
| Backup wrong password / tamper / header / truncation (incl. chunk boundary) | Backup › wrongPasswordTamperingAndTruncationChangeNothing; AeadPolicy › reordered…, roundTrips…, unsafeKdf… | pass |
| Backup quota / unavailable items | Backup › restoreThatWouldExceedTheQuotaChangesNothing, unavailableItems… | pass |
| Backup bounded memory (160 MiB under -Xmx48m) | Backup › largeLibraryExportsAndRestoresUnderA48MegabyteHeap | pass |
| Content sniffing | AeadPolicy › snifferRecognises… | pass |
| Inline decision + thumbnail selection (quotes/avatars excluded) | InlinePresentation › 4 tests | pass |
| Gallery tokens single-use/expiry | InlinePresentation › galleryTokens… | pass |
| Persistence over time: 1 d … 2 years, restart + deep reconcile + trash purge at each step, WhatsApp source file deleted | Persistence › keptPhotosAndVideosStayOpenableForTwoYearsAcrossRestartsWithoutTheWhatsAppFile | pass |
| Persistence past 512 MiB (~600 MiB, re-hashed after 90 days) | Persistence › libraryLargerThan512MegabytesKeepsEveryItemOpenable | pass |
| Limit reached / device full: new refused, old never evicted (400/60 days later) | Persistence › reachingTheLimit…, lowDeviceStorage… | pass |
| Only trashed items expire (30-day boundary) | Persistence › onlyItemsTheUserTrashedEverExpire | pass |
| Backup restored 3–4 years later opens every item | Persistence › backupRestoredYearsLaterStillOpensEveryItem | pass |
| Received view-once kept automatically only with the module switch; status and normal media never auto-kept | ViewOnceKeepAndRestore › receivedViewOnceIsKeptAutomatically… | pass |
| Auto-kept view-once opens on day 13, 14, 15, 30, 31, 60, 61, 365 after WhatsApp dropped its file | ViewOnceKeepAndRestore › autoKeptViewOnceOpensAfterWhatsAppExpiresItAtAnyAge | pass |
| Repeated automatic keeps are idempotent | ViewOnceKeepAndRestore › repeatedAutomaticKeeps… | pass |
| WhatsApp path resolution and restore-target safety | ViewOnceKeepAndRestore › storedPaths…, restoreIntoWhatsApp… | pass |
| Row tokens: delayed callback after rebind to a non-media message, after rebind to another media message, same-message rebind, cross-thread race | InlineBinder › delayedCallback…, lateMainThreadPost…, rebindToTheSameMessage…, callbackRacingARebind… | pass |
| Cache invalidation after trash / restore / purge / damage / repair (new thumbnail key) / account switch / WhatsApp flavour / settings change; negative TTL; failures not cached | InlineBinder › positiveCache…, repairChanges…, accountSwitch…, settingsChange…, negativeAnswers…, unreachableLibrary… | pass |
| Generation bumps on visible mutations only and persists across restarts | InlineBinder › generationChangesOnEveryVisibleMutationOnly | pass |
| Provider read access needs same package and account | InlineBinder › providerAccessRequiresTheSamePackageAndAccount | pass |
| Inline host without a native thumbnail (view-once body, media frame); never whole bubble/quotes | InlinePresentation › presentationDoesNotNeedANativeThumbnail, hostSelectionNever… | pass |
| Copy opens in a fresh process with network disabled after the original became unavailable | Persistence › copyOpensInAFreshOfflineProcess… | pass |
| Restore into WhatsApp: off ⇒ nothing written; exact bytes at recorded path; never overwrite; never view-once; respects known user deletion; refuses outside roots/traversal; interrupted copy leaves no file | WhatsAppRestoreAndAlbums › restoresExactBytes…, neverOverwrites…, refusesPaths…, interruptedCopy… | pass |
| View-once vs normal separation for tab and save album; flag survives backup | WhatsAppRestoreAndAlbums › viewOnceAndNormalMediaStaySeparate…, viewOnceFlagSurvivesBackupAndRestore | pass |
| Restore never replaces a file WhatsApp creates between check and publish (hard-link and no-hard-link paths); exclusive-create fallback restores exact bytes | SecondReviewFixes › fileCreatedByWhatsApp…(×2), withoutHardLinks… | pass |
| Concurrent restores: unique temporaries, one winner per name, no mixed file, no leftovers | SecondReviewFixes › concurrentRestores… | pass (also exposed and fixed a concurrent `mkdirs()` bug) |
| Killed-process recovery: temps and partial targets removed, complete and foreign files kept, damaged journal authorises nothing | SecondReviewFixes › recoveryAfterAKilledProcess…, journalPointsAtTheRealTemp… | pass |
| Gallery export publishes only after close; failed write/close/publish (update ≠ 1) reported and cleaned | SecondReviewFixes › exportPublishesOnlyAfter…, exportFailsAndCleansUp… | pass |
| Schema 3: uncertain origins unknown, settled once by WhatsApp, never overwriting known flags, account-scoped | SecondReviewFixes › migrationMarksUncertain…, newImportsAlwaysRecord… | pass |
| Schema 1 → 2 migration keeps rows and marks view-once | Schema › version1CatalogMigratesToVersion2… | pass |
| Media-screen contact JID recognition | WhatsAppRestoreAndAlbums › mediaScreenContactIsRecognised… | pass |
| Android SQLite adapter, provider, activities, hook | compiled (`compileWhatsappDebugJavaWithJavac`), lint run | **static only** |
| Liquid Glass compatibility | code audit only | **device pending** |

## Device validation still required

Nothing below has been observed on a phone. Each item names what proves it.

1. `MediaLibraryHook` installs on WhatsApp 2.26.38.74: LSPosed log has no `MediaLibrary … unavailable`.
2. **Keep in library** appears in the media viewer menu for a conventional photo and video, own and
   received, and **not** for received view-once or a contact's status.
3. The `ParcelFileDescriptor` crosses `ContentResolver.call` (toast "Kept in the media library");
   if Android strips it, the provider answers `INVALID_REQUEST` and the toast says it could not
   keep the media.
4. Conversation ⋮ → Media library opens the gallery for that chat only (WA vs W4B, two accounts).
5. Inline overlay: delete WhatsApp's local file of a kept photo (or use an old message whose
   download fails) → the row shows the copy with the "Library copy" badge; ▶ opens it; long-press
   still selects the message; scrolling/recycling never shows a copy on the wrong row.
6. Liquid Glass Text Bubbles on: bubble glass, contrast colours and gestures unchanged on rows
   with and without an overlay (compare with `glass trace` FREQUENCY counters).
7. Video playback in the viewer from the provider URI on Android 17.
8. Export/restore a multi-GB library to a SAF location; force-stop during import/restore, reopen,
   then **Verify files** reports no damage.
9. `getMyUserJid` resolves (otherwise import fails closed with "account could not be identified").
10. With "Keep received view-once" on: a new view-once is copied without opening it (sweep log
    `MediaLibrary view-once sweep: with_file=N newly_kept=M`), and once WhatsApp stops providing it (e.g. "Photo expired") the bubble shows
    the copy and a tap opens it instead of "Photo expired". Confirm the sweep's SQL matches
    2.26.38.74's schema and that `message_media.file_path` resolves for view-once files.
11. Restore into WhatsApp: delete a kept normal photo's file from WhatsApp's folder, scroll to it
    → toast "Restored from the media library", the bubble opens natively and the photo is listed in
    the contact's "Media, links and docs".
12. Two accounts in one WhatsApp, and WhatsApp + WhatsApp Business: after switching, no copy,
    thumbnail or gallery item of the other account is shown or opened.
14. WhatsApp → contact → Media: a "View once" chip appears only for contacts with kept view-once
    items; it lists only view-once copies; Photos/Videos are unchanged; tapping a native chip hides
    the grid; tapping an item opens it.
15. Download View Once saves into `DCIM/WhatsApp View Once`; module "Save to gallery" saves view-once to
    `DCIM/WhatsApp View Once` and normal media to `DCIM/WhatsApp`; both appear as albums in the phone
    gallery.
16. First start with the library on: LSPosed log `MediaLibrary view-once import: initial=true completed=true
    scanned=N imported=M …` and the toast "Media library: M view-once items kept"; a second start
    shows `initial=false`; a new view-once is kept within 10 minutes without opening it.
17. Verify files: the report counts match reality; a file copied into `files/media_library/blobs`
    by hand is reported and quarantined, never deleted without confirmation.
18. Switch a chat between phone number and LID addressing (or update WhatsApp): kept media stays
    attached to the same messages; no duplicate rows appear.
19. "View once" chip (contact → Media): outline, icon, weight and colour match the native chips in
    light and dark theme; selecting it shows the grid; a native chip hides it and the native chip's
    own selection still works.
20. View-once bubble: a received view-once whose file WhatsApp still has shows the image as a
    media-sized bubble (no "1 Photo" pill, no badge) once kept; the same after expiry; own sent
    view-once with a copy likewise; tap opens the copy in the module viewer and the message is not
    consumed (WhatsApp still lists it as unopened); long-press still selects the row; **Back from
    the viewer returns to the conversation** (the gallery activity that launches the viewer for a
    direct-open request finishes itself; owner found it returning to the library on df361a3).
21. Fast scrolling/recycling through view-once and text rows: no row keeps a resized body, hidden
    "1 Photo" children or a stale image after rebinding; with Text Bubbles Liquid Glass on, the
    enlarged bubble's glass and the view-once fill clearing behave as before.
13. Scroll fast through a chat mixing kept media and text: no copy ever appears on a text row or on
    the wrong media row (row tokens), and deleting/restoring an item in the gallery is reflected
    when returning to the chat.

## Reusable in InstaEclipse

The following are app-agnostic and can be copied as a package (`com.waenhancer.library.core`),
with no WhatsApp or Xposed dependency:

- `Sql` + `AndroidSql` (+ `JdbcSql` for JVM tests) — test production SQL off-device.
- `LibrarySchema` pattern — versioned, transactional, count/integrity-checked migrations.
- `BlobStore` — content-addressed storage with verified reuse, staging and atomic commit.
- `LibraryLock` — thread + process lock.
- `StreamingAead` — streaming password AEAD for any large export.
- `LibraryBackup` two-phase restore pattern; `MediaSniffer`; `GalleryAccess` capability tokens.
- `InlinePresentation.Node` selector pattern for choosing native views testably.

App-specific parts to replace: `ImportPolicy` facts (InstaEclipse's ephemeral rules), the
identity tuple in `ImportRequest`/`items`, `MediaLibraryProvider` caller packages and
`MediaLibraryHook`.
