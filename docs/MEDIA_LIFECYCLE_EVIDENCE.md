# Media lifecycle: what is proven and what is unknown

Status 2026-11-26. This document keeps two questions apart that are easy to conflate:

1. **Independent persistence** — does a copy kept by the module's media library stay available
   on its own? This is about module code and is tested.
2. **WhatsApp's lifecycle** — when and why does WhatsApp stop providing a media file ("Photo
   expired")? This is about WhatsApp's closed code and server, and is **not** established.

## 1. Independent persistence — proven by tests (JVM, real SQLite and files)

Proven for the library core (`com.waenhancer.library.core`), with the cited tests passing in
`./gradlew :app:testWhatsappDebugUnitTest`:

| Claim | Evidence |
| --- | --- |
| A kept copy keeps its exact bytes after WhatsApp's original file is deleted | `MediaLibraryPersistenceTest.keptPhotosAndVideos…WithoutTheWhatsAppFile`, `ViewOnceKeepAndRestoreTest.autoKeptViewOnceOpens…` |
| …at simulated ages from 1 day to 2 years, with a restart and full maintenance (deep re-hash, trash purge) at each step | same tests (checkpoints 1, 7, 13–15, 30–32, 60–62, 90, 180, 365, 730 days) |
| …in a new OS process with every network path disabled (proxy selector and socket factory throw) | `MediaLibraryPersistenceTest.copyOpensInAFreshOfflineProcessAfterTheOriginalBecameUnavailable` |
| …when the library exceeds 512 MiB (~600 MiB) | `libraryLargerThan512MegabytesKeepsEveryItemOpenable` |
| A full quota or a full device refuses new media and never evicts old media | `reachingTheLimit…`, `lowDeviceStorage…` |
| Only items the user moved to the trash are ever removed (after 30 days) | `onlyItemsTheUserTrashedEverExpire` |
| A backup restored years later opens every item | `backupRestoredYearsLaterStillOpensEveryItem` |
| No module code removes media by age; the only 512 MiB limit in the module is the Deleted-for-Me vault's, which refuses new copies and never deletes | code audit (`DeletedMediaVault.preserve`, `grep` for age-based deletion) |

Not proven (requires the device):

- that the WhatsApp-side hook actually copies media on WhatsApp 2.26.38.74 (menu, row, viewer and
  database-sweep triggers), and that the file descriptor crosses `ContentResolver.call`;
- that the Android adapter (`AndroidSql`, platform SQLite 3.22+) behaves like the JDBC adapter
  used in tests (the SQL is the same; the adapter itself is untested on device);
- that presentation in the chat (overlay, tap redirect, restore into WhatsApp) works and leaves
  scrolling, recycling, gestures and Liquid Glass unchanged.

## 2. WhatsApp's lifecycle — unknown, hypotheses only

Observed by the owner on Galaxy S25 Ultra / Android 17 / WhatsApp 2.26.38.74 (not reproduced here):

- recent view-once media remains viewable offline and after force-stop with Unlimited View Once;
- an older received view-once message keeps its bubble but shows the native "Photo expired" dialog
  (screenshot: message of 2026-03-06, viewed 2026-11);
- Download View Once exports only on tap; the configured folder held one explicitly saved photo
  (41,551 bytes, readable).

Unknown:

| Question | Status |
| --- | --- |
| What triggers "Photo expired": elapsed time, the unopened/opened state, local file deletion, server-side media URL expiry, or a combination | **Unknown.** WhatsApp's public help states that unopened view-once media expires after a period (14 days at the time of writing); this was not verified on this build, and it does not explain by itself what happens to media viewed through Unlimited View Once. |
| Whether WhatsApp deletes the local view-once file, the decryption key, or only marks the message | Unknown |
| Where WhatsApp 2.26.38.74 stores received view-once files and whether `message_media.file_path` points to them | Unknown (the module's database sweep assumes it does) |
| Whether normal (non view-once) media files are removed by WhatsApp over time on this device | Unknown; storage-management deletion by the user is the known case |
| Whether writing a kept file back to WhatsApp's recorded path makes WhatsApp, its media gallery and its backup treat it as downloaded | Unknown until the device check; the feature is opt-in and off by default for that reason |
| Where WhatsApp's native manual save puts view-once media | Owner observation: Download View Once (module) saved to `Download/WaEnhancer/WhatsApp/View Once`; WhatsApp's own save of normal media goes to `DCIM/WhatsApp`. Now changed to `Pictures/View Once` for view-once. |

Evidence that would settle these (device, no message content needed): for one new view-once,
record `message.message_type`, `message_media.file_path`, file existence and size, and the
view-once state columns at 0 h, 1 day, 7 days, 13, 14, 15 days and after "Photo expired" first
appears, with Unlimited View Once on and off. The module's diagnostics can log only these
metadata fields.

Consequence for the design: the library does not depend on the answer. It copies media while
WhatsApp still has the file and keeps it independently; it cannot recover media WhatsApp dropped
before the copy was made.
