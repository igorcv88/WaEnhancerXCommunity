# Upstream ports of 2026-11-22/23: integrated review (2026-11-27)

PRs #165–#173 (porting branches) were reviewed together before merging into `master`
(`acc231d`). #173 (`port/cache-typed-reflection-fields`) contains every other porting
branch as an ancestor. #172's two commits are in #173 as cherry-picks: same patch,
different SHAs. Merging #172 after #173 leaves no diff.

| PR | Change | Upstream source (Dev4Mod/WaEnhancer) | Adaptation |
|---|---|---|---|
| #165 | NinePatch colour read in bounded column batches | 4da202bba | Keeps column-major tie-break; buffer ≤ 16384 px |
| #166 | Remaining Disable Meta AI ABProps flags | a97221c3e | As upstream |
| #167 | Profile photo request arguments resolved by type | b47ebf9f9 | Previous indices first; ambiguous match → failure, which is caught and logged |
| #168 | Newsletter detection; JID equality on phone, else LID | 21cafe305946 | `equals`/`hashCode` use the phone JID, else the LID JID; a JID with neither is equal only to itself |
| #169 | Original-message-key fallback; played-receipt constructor with extra booleans | 21cafe305946, 47d556fca | String anchor kept; an ambiguous fingerprint is rejected; non-boolean extra primitives are rejected |
| #170 | Skip `publishResults` for the plain `FilterResults` of a failed filtering | 823c9339 | As upstream (exact class equality: WhatsApp's successful results are its own subclass; a plain one means `performFiltering` threw) |
| #171 | Combined validation of #165–#170 | — | — |
| #172 | DexKit `findFirst` for exact class-name searches | e0fb0107e | Kotlin shim (`DexKitClassQuery`): the flag is hidden from Java; the PhoneUserJid fallback reuses the query |
| #173 | Typed field cache; isolate backgrounds before tint; contact-picker fallback | 5aa4c23b, ad6bf9a9, 6c4ee436 (picker part) | Per-class cache keyed (owner, type, exact/assignable); callers still receive new lists; picker fallback only when unambiguous; `LockedChatsEnhancer` handles null |

## Integrated review findings

- No overlap in files with #176 (media library) or #175 (gesture diagnostic). Combined
  `master + #173 + #172 + #176` was tested locally: 104 suites / 814 tests, 0 failures.
  Lint ran, and debug compilation passes. The only lint error in a touched file
  (`Others.java` SoonBlockedPrivateApi) is pre-existing; the port only moved its line number.
- `UserJid.equals` change (#168). Before, all LID-only JIDs (phone `null`) were equal.
  `SeenTick` groups receipts in a `HashMap<UserJid, …>`, so those chats were merged into one
  group. They are now separate. Two `UserJid`s for the same contact where only one has
  resolved the phone JID still compare unequal, as before.
- `replaceColor(…, isolateState=true)` (#173) calls `mutate()`. Liquid Glass registers
  factory bubbles by instance identity, which `mutate()` keeps, and adopts copies by the
  ConstantState recorded at the factory, which `mutate()` does not change. The effects of a
  theme recolour on drawables that share state are now confined to the walked view. Shared
  backgrounds that were recoloured by accident before may now stay native: this needs a
  check on the device.
- New lookups fail closed: an ambiguous match → `NoSuchMethodException` or `null`, which
  the feature handles.

## Device validation still open (none was done by the reviewing agent)

Theme recolouring with a custom theme and CSS rules; edited-message original key and
played receipts on 2.26.38.74; Separate Groups tab with a search filter; profile-photo
blocked check; locked chats contact picker; Disable Meta AI.
