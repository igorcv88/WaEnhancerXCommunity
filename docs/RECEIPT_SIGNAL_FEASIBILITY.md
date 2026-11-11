# Receipt signal feasibility — opt-in research, 2026-11-11

## What is verified, and what is not

WhatsApp's native message read receipt is a specific remotely received event. A delivered receipt, online state, reaction acknowledgement, local reaction \`read\` field and multi-device \`read-self\` are **not** interchangeable with that event. The existence of a field in a client protocol object does not prove that the sender of a message receives it with the recipient's read-receipts disabled.

Useful public implementations:
- [Baileys receiving updates](https://github.com/WhiskeySockets/baileys.wiki-site/blob/main/docs/socket/receiving-updates.md): distinguishes \`messages.update\`, \`messages.reaction\`, and \`message-receipt.update\`.
- [Baileys event buffer](https://github.com/WhiskeySockets/Baileys/blob/master/src/Utils/event-buffer.ts): records updates with message keys, without making them equivalent.
- [whatsapp-web.js Reaction](https://github.com/wwebjs/whatsapp-web.js/blob/main/docs/structures_Reaction.js.html): has \`read\` and \`ack\` fields, but the intended audience of these fields needs controlled-device verification.
- [WhatsApp Help Center](https://faq.whatsapp.com/iphone/28041116?lang=pt_pt): message, group, and voice playback receipt semantics.
- [whats-incognito](https://github.com/webmaia/whats-incognito): Web browser websocket interception; not an Android in-process transport API and not a source of proof of recipient-side information availability.

Search of public GitHub and XDA surfaced no independently verified Android implementation able to reliably convert a contact's disabled private-chat read receipts into blue-check-equivalent evidence.

## Candidate evidence and claims

| Candidate | Independent source | What can be asserted when observed on sender | Not justified |
| --- | --- | --- | --- |
| Incoming \`read\` for specific message | Receipt store/protocol | Read confirmed for that message | Other messages read |
| Incoming \`played\` | Specific audio receipt | Voice note played | Other messages read |
| Reaction from contact referencing sent message | Message/reaction callback | Contact interacted with this message | Entire conversation read |
| Reply quoting sent message | Incoming message quote key | Contact accessed referenced content | Every earlier/later message read |
| Reaction \`read\` or \`ack\` field | Web client model, unverified on Android | Nothing until who receives it is established | Hidden receipt discovered |
| Typing/recording presence for chat | Presence callback | Activity associated with chat at that time | Specific message was read |
| Online, delivery, \`read-self\` | Presence/receipt | Online/delivered/own-device sync only | Read inferred |

Only an event with a verified target-message key may alter that message's inferred state. Conversation-level evidence must never automatically colour all message ticks.

## Phase 1 implementation: bounded receipt-store probe

**PR branch:** \`feat/receipt-signal-probe-phase1-20261111\`.

Adds \`receipt_signal_probe\`, off by default, in conversation settings (normal and embedded), with an English/Portuguese description and a public-schema entry. Turning it on arms a **10-minute / 128 matched-record / 512 total-record** diagnostic window *in that WhatsApp process*. The existing \`SeenTick\` hook picks the next outbound, direct 1:1 message; no other send method or read policy is changed. The existing \`ToastViewer\` receipt-store callback passes its input to the probe *before* filtering for receipt code 13. It scans bounded metadata fields of *all* receipt record types and compares opaque message IDs/JIDs/row IDs only in RAM. Matching records are logged as message/row/chat-only; unrelated records are aggregate-counted. No message text, emoji, profile, phone, actual JID, message ID, raw stanza or media is logged.

The local logcat tag is \`WaEXReceiptProbe\`. It prints \`armed\`, \`target_selected\`, \`receipt_insert\`, \`unmatched_aggregate\`, \`stopped\`, with timing, obfuscated class schema and up to eight integer code fields. A \`chat_only\` event **does not** establish a read of the selected message. This implementation is research-only: it does not change tick colours or privacy toggles.

This is **not yet an exhaustive network/decoded-protocol probe**. Receipt-store callbacks do not include all reaction messages, conversation-typing updates, multi-device synchronization or protocol nodes rejected upstream of the store. Android broadcasts are app-local signals, not direct WhatsApp-server proof. Capturing raw TLS/encrypted traffic would not help without decoding and would create an unnecessary data-exposure surface.

### Device test (two owned test accounts)

1. Install the signed PR build; keep account A running WhatsApp with this module and account B on a second phone/client. Start from the **A** side. Enable **Investigate receipt signals (temporary)** in Conversation settings. The setting is independent of the Element Inspector overlay.
2. Send one **new** test message from A to B (not a group/status). The log must show \`stage=target_selected\` before any meaningful match. Do not send another new message until the run ends.
3. On B with read receipts **enabled**: (a) let the message deliver but do not open it; (b) open/read it; (c) react to that message; (d) quote-reply to it. Record manually what happened and when. Each condition needs a new probe window/target.
4. Repeat on B with read receipts **disabled**: delivery-only; open; react; react then open the reaction; quote-reply. Also do a negative control (B online without opening this chat). Compare which \`receipt_insert\` events are actually received and which are absent.
5. Turn the toggle **off** after testing, and restart WhatsApp before rearming it for another run. The in-process window expires at 10 min but an enabled preference starts a fresh window after a process restart.

From rooted Termux, capture only this dedicated tag (no chat content):
\`\`\`sh
su -c 'logcat -d -v threadtime -s WaEXReceiptProbe:I' > ~/waex-receipt-probe.txt
\`\`\`
Or follow it live:
\`\`\`sh
su -c 'logcat -v threadtime -s WaEXReceiptProbe:I'
\`\`\`
The report stays local unless explicitly shared. Do **not** clear WhatsApp data and do not share exported raw DEX/SQLite databases.

**Interpretation rule:** if no new record appears when B opens a reaction with receipts disabled, this does *not* prove that no other signal exists; this phase covers only the receipt store. A type code observed for B cannot be labelled "read" without a matched message and controlled positive/negative comparison.

## Phase 2: decoded ingress reconnaissance (not implemented)

Before implementing UI or recolouring any ticks, identify an actual post-decryption and pre-routing receipt/notification ingress method in the WhatsApp version installed on the test device (DexKit signatures, uniqueness checks and method-shape validation). Capture **only** limited envelope metadata: node/event family; allowlisted attribute names and constant enum type values; direction; whether a message ID equals the RAM-only selected target; whether sender/chat identity equals the RAM-only target; monotonic timing. Never print raw attribute strings, stanza XML, protobuf bytes, addresses, message bodies, reaction emojis or contact IDs. Limit to one selected chat, a short armed window, and a fixed event budget. Do not modify return values or dispatch order.

Then add reaction insertion/update and quote-key events at their verified message-local callbacks, plus a small test-visible classification layer distinguishing \`CONFIRMED_READ\`, \`CONFIRMED_PLAYED\`, \`INTERACTION_ONLY\`, and \`UNRELATED/UNKNOWN\`. Use separate ground-truth controls for linked devices and PN/LID changes.

## User-facing feature proposal, conditional on evidence

Potential setting: **Show indirect activity indicators** / **Mostrar indícios de atividade**.
Description: *"Some events, such as reactions or replies referencing a specific message, can suggest interaction even when read receipts are disabled. They do not prove every message was read. Only matched, verified evidence is marked."*

A colour selector with four contrasting presets may appear only when enabled. Keep grey delivered and native blue read receipts unchanged. A separate chosen colour, applied to the same two-tick geometry, would mean **inferred interaction only**, never a forged blue-read state. Disable by default; do not persist any inferred tick or render it on a group/media row until attribution and lifecycle tests pass.
