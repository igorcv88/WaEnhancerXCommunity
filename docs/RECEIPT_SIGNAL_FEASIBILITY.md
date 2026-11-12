# Receipt signal feasibility — opt-in research, 2026-11-11

## What is verified, and what is not

WhatsApp's native message read receipt is a specific remotely received event. A delivered receipt, online state, reaction acknowledgement, local reaction `read` field and multi-device `read-self` are **not** interchangeable with that event. The existence of a field in a client protocol object does not prove that the sender of a message receives it with the recipient's read-receipts disabled.

Useful public implementations:
- [Baileys receiving updates](https://github.com/WhiskeySockets/baileys.wiki-site/blob/main/docs/socket/receiving-updates.md): distinguishes `messages.update`, `messages.reaction`, and `message-receipt.update`.
- [Baileys event buffer](https://github.com/WhiskeySockets/Baileys/blob/master/src/Utils/event-buffer.ts): records updates with message keys, without making them equivalent.
- [whatsapp-web.js Reaction](https://github.com/wwebjs/whatsapp-web.js/blob/main/docs/structures_Reaction.js.html): has `read` and `ack` fields, but the intended audience of these fields needs controlled-device verification.
- [WhatsApp Help Center](https://faq.whatsapp.com/iphone/28041116?lang=pt_pt): message, group, and voice playback receipt semantics.
- [whats-incognito](https://github.com/webmaia/whats-incognito): Web browser websocket interception; not an Android in-process transport API and not a source of proof of recipient-side information availability.

Search of public GitHub and XDA surfaced no independently verified Android implementation able to reliably convert a contact's disabled private-chat read receipts into blue-check-equivalent evidence.

## Candidate evidence and claims

| Candidate | Independent source | What can be asserted when observed on sender | Not justified |
| --- | --- | --- | --- |
| Incoming `read` for specific message | Receipt store/protocol | Read confirmed for that message | Other messages read |
| Incoming `played` | Specific audio receipt | Voice note played | Other messages read |
| Reaction from contact referencing sent message | Message/reaction callback | Contact interacted with this message | Entire conversation read |
| Reply quoting sent message | Incoming message quote key | Contact accessed referenced content | Every earlier/later message read |
| Reaction `read` or `ack` field | Web client model, unverified on Android | Nothing until who receives it is established | Hidden receipt discovered |
| Typing/recording presence for chat | Presence callback | Activity associated with chat at that time | Specific message was read |
| Online, delivery, `read-self` | Presence/receipt | Online/delivered/own-device sync only | Read inferred |

Only an event with a verified target-message key may alter that message's inferred state. Conversation-level evidence must never automatically colour all message ticks.

## Phase 1 implementation: bounded receipt-store probe

**PR branch:** `feat/receipt-signal-probe-phase1-20261111`.

Adds `receipt_signal_probe`, off by default, in conversation settings (normal and embedded), with an English/Portuguese description and a public-schema entry. Turning it on arms a **10-minute / 128 matched-record / 512 total-record** diagnostic window *in that WhatsApp process*. The existing `SeenTick` hook selects the first outbound direct 1:1 conversation and tracks up to **12 outgoing messages within that chat**, each assigned an anonymous slot. It does not switch chats during the same window. A separate opt-in hook at the existing `WppCore` reaction-action method observes **reaction sending attempts** when the installed WhatsApp version uses that method. These attempts do not prove delivery or that the other person viewed the reaction. The existing `ToastViewer` receipt-store callback passes its input to the probe *before* filtering for receipt code 13. It scans bounded metadata fields of *all* receipt record types and compares opaque message IDs/JIDs/row IDs only in RAM. Matching records are logged as `message_or_row` (anonymous slot index) or `chat_only`; unrelated events are aggregate-counted. The first observed class also emits `receipt_schema`, containing only field names and Java types, not field values. No message text, emoji, profile, phone, actual JID, message ID, raw stanza or media is logged.

The local logcat tag is `WaEXReceiptProbe`. It prints `armed`, `target_selected`, `reaction_hook`, `reaction_attempt`, `receipt_schema`, `receipt_insert`, `unmatched_aggregate`, `stopped`, with timing, field shape and up to eight integer code fields. A `chat_only` event **does not** establish a read of the selected message. This implementation is research-only: it does not change tick colours or privacy toggles.

This is **not yet an exhaustive network/decoded-protocol probe**. Receipt-store callbacks do not include all reaction messages, conversation-typing updates, multi-device synchronization or protocol nodes rejected upstream of the store. Android broadcasts are app-local signals, not direct WhatsApp-server proof. Capturing raw TLS/encrypted traffic would not help without decoding and would create an unnecessary data-exposure surface.

### Device test (two owned test accounts)

1. Install the signed PR build; keep account A running WhatsApp with this module and account B on a second phone/client. Start from the **A** side. Enable **Investigate receipt signals (temporary)** in Conversation settings. The setting is independent of the Element Inspector overlay.
2. Send a **new** test message from A to B (not a group/status). The log must show `stage=target_selected slot=0` before any meaningful match. More messages in that **same chat** can receive slots 1–11. Messages to other chats are ignored until the window ends.
3. On B with read receipts **enabled**: (a) let the message deliver but do not open it; (b) open/read it; (c) react to that message; (d) quote-reply to it. On A, react to a **message received from B**, then ask B to open the conversation again. The optional `reaction_attempt` establishes A's own outgoing action only if the native UI uses the hooked method; it does not observe B viewing the reaction. Note the times of each action and use independent new sessions for controls.
4. Repeat on B with read receipts **disabled**: delivery-only; open; react; react then open the reaction; quote-reply. Also do a negative control (B online without opening this chat). Compare which `receipt_insert` events are actually received and which are absent.
5. Turn the toggle **off** after testing, and restart WhatsApp before rearming it for another run. The in-process window expires at 10 min but an enabled preference starts a fresh window after a process restart.

From rooted Termux, capture only this dedicated tag (no chat content). **Start the continuous log before arming**, or the first events may be lost when Android rotates the log buffer:
```sh
su -c 'logcat -v threadtime -s WaEXReceiptProbe:I' > ~/waex-receipt-probe-live.txt
```
Stop capture with Ctrl+C. To export only the currently retained historical buffer instead:
```sh
su -c 'logcat -d -v threadtime -s WaEXReceiptProbe:I' > ~/waex-receipt-probe-snapshot.txt
```
The report stays local unless explicitly shared. Do **not** clear WhatsApp data and do not share exported raw DEX/SQLite databases.

**Interpretation rule:** if no new record appears when B opens a reaction with receipts disabled, this does *not* prove that no other signal exists; this phase covers only the receipt store. A type code observed for B cannot be labelled "read" without a matched message and controlled positive/negative comparison.

## Phase 2: decoded ingress reconnaissance (not implemented)

Before implementing UI or recolouring any ticks, identify an actual post-decryption and pre-routing receipt/notification ingress method in the WhatsApp version installed on the test device (DexKit signatures, uniqueness checks and method-shape validation). Capture **only** limited envelope metadata: node/event family; allowlisted attribute names and constant enum type values; direction; whether a message ID equals the RAM-only selected target; whether sender/chat identity equals the RAM-only target; monotonic timing. Never print raw attribute strings, stanza XML, protobuf bytes, addresses, message bodies, reaction emojis or contact IDs. Limit to one selected chat, a short armed window, and a fixed event budget. Do not modify return values or dispatch order.

Then add reaction insertion/update and quote-key events at their verified message-local callbacks, plus a small test-visible classification layer distinguishing `CONFIRMED_READ`, `CONFIRMED_PLAYED`, `INTERACTION_ONLY`, and `UNRELATED/UNKNOWN`. Use separate ground-truth controls for linked devices and PN/LID changes.

## User-facing feature proposal, conditional on evidence

Potential setting: **Show indirect activity indicators** / **Mostrar indícios de atividade**.
Description: *"Some events, such as reactions or replies referencing a specific message, can suggest interaction even when read receipts are disabled. They do not prove every message was read. Only matched, verified evidence is marked."*

A colour selector with four contrasting presets may appear only when enabled. Keep grey delivered and native blue read receipts unchanged. A separate chosen colour, applied to the same two-tick geometry, would mean **inferred interaction only**, never a forged blue-read state. Disable by default; do not persist any inferred tick or render it on a group/media row until attribution and lifecycle tests pass.


### First controlled device observation (11 Nov 2026)

After A sent messages, reacted to an incoming message and B opened the conversation again, the exported log contained only three surviving records: 20:04:16.847, 20:04:23.915 and 20:04:32.208 local. Each reported `schema=X.9xM ints=A00:5 match=chat_only field_count=5`. The three records match the chat, **not** an individual outbound message; the meaning of numeric code **5** is **not established**. The existing `ToastViewer` filters on type **13** for its read notification, but that is not a mapping for every receipt-store code.

The elapsed values show the session began around **19:57:46.736**; the original probe selected only the first outgoing message. Later 20:04 messages and a reaction on B's incoming message were distinct objects. The first `armed` and `target_selected` logs were absent in the exported snapshot, so the specific first message is unknown. Three events around B's reopening could be delivery updates for the messages A sent then, or another kind of update; their cause cannot be inferred from coincidence. This is positive evidence that the receipt-storage hook runs, not evidence of a hidden read receipt or of a reaction-read callback.

The updated phase 1 probe observes up to twelve outgoing messages in a single selected chat (separate anonymous slots), prints schema types of matched receipt objects, and observes an outgoing reaction **attempt** through the existing known WhatsApp action method if that method handles native reactions on this version. It still does **not** observe all inbound reaction-read or decrypted network envelopes. Absence of a receipt is not evidence that no other signal exists.
