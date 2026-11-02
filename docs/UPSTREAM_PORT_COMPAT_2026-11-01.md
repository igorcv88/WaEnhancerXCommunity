# Upstream compatibility ports (PR #125) — 2026-11-01

This file records what PR #125 (`fix/whatsapp-resolver-compat-20261101`) integrates, so the
repository keeps the record even if this PR merges before the audit/handoff PR #127. The full
disposition of every upstream commit is in `UPSTREAM_AUDIT_AND_GLASS_REVIEW_2026-10-31.md`
("Upstream porting pass — 2026-11-01") and `UPSTREAM_AUDIT_WAEX_LEDGER_2026-11-01.md` (#127).

Source: `Dev4Mod/WaEnhancer@36c22aeda030327704b5ee0b099f060fd42c1120` (GPL-3.0), adapted to the
Java runtime. No WhatsApp version was added to the compatibility list.

| Upstream | Community change | Exclusions / adaptation |
| --- | --- | --- |
| `efdd903` | `ShareLimit` + `MultiSelectionLimitArgs`: limit is the first `int` that is not a resource ID; boolean block flags cleared | Community keeps its configurable `customforwardlimit` instead of `Int.MAX_VALUE` |
| `b84f5f6` | `loadGetViewConversationMethod`: `LayoutInflater` in any parameter position | — |
| `02e4fc5` | `loadGhostModeMethod`: composing-state `int` in any position | `TypingPrivacy` already scans arguments dynamically |
| `2d2301a` | Outgoing-call signature fallback (unique match only); `NewsletterHeaderDataItem(enableExploreButton=` anchor with legacy fallback; locked-chats getter via `LockedConversationsFragment` | Ambiguous fragment route returns to the legacy notification route |
| `f2551e7` | Abstract/interface `SharedPreferences` classes skipped; each getter hooked independently | — |
| `01a8bb7` | `RevokeResultPolicy`: blocked revoke results follow the declared return type (store-result enum, int, long, boolean, result object with WhatsApp's `None` ack via `loadNoneIncomingAckClass`) | Unsupported return types let WhatsApp proceed |
| `790a7e2` | `PinnedLimit`: inline-safe pin/trim/sync hooks when anchors exist; legacy size-faking path otherwise; with the option off, no process-wide `ArrayList(int)`/`LinkedHashSet(int)` hooks | Always-on in-chat pinned-message limit (60) unchanged |
| `1abe476` | Anti-disappearing `UPDATE` only touches rows not yet extended | **Partial**: running the update through WhatsApp's own msgstore connection is not ported |

Not ported from the same area: X `21f7aee` (already covered by DexKit `returnType` matchers), X `28d811f`
(X-specific chat-limit fallbacks; `790a7e2` used instead), Dev4Mod `509c4ec` (already present), `5db954a`
(no sticker-outline hook in Community), `cc51ac7` (no version declaration without DEX evidence).

## Validation limits

- WhatsApp/WhatsApp Business APKs for 2.26.39–2.26.42 were not obtainable in the cloud session
  (APKMirror/APKPure HTTP 403): **no resolver here was verified against a target DEX**.
- Static: `CompatPolicyTest` (5 tests); on master + #123 + #124 + #125 + #126 the Debug build and
  unit tests pass. Compilation of this branch requires #123.

## Device checks (Galaxy S25 Ultra)

1. Forward beyond the old limit, including groups and multiple channels.
2. Option on: pin more than three chats, trigger a sync, confirm pins survive and the regular pin icon; option off: native behavior.
3. Status and message revoke with Anti-Revoke on: no `ClassCastException` in the WhatsApp log.
4. Record which route logged: `[WAE] PinnedLimit`, `LockedChats fragment route`, `SharedPreferencesWrapper ... not hooked`, StartOutgoingCall fallback.
