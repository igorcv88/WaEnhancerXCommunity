# Liquid Glass functional reconciliation — 2026-10-31

Base: master c1c1b0a9 (PR #113). Branch: fix/composer-functional-reconciliation.

## Physical evidence and corrected baseline

The user's written reply is broken. A live recording or PANE_DRAW does not establish correct final composition. Cancelling recording can leave an oversized input until navigation; initial recorder glass can require outside interaction; voice reply and mic positioning are intermittent. Pausing, resuming, cancelling and sending were exercised voluntarily. Navigation is not evidence of a crash.

Local evidence reviewed: WaEX_Diag112_20261031_163439.txt and Screenshot_20261031_133409_WhatsApp.png / Screenshot_20261031_133413_WhatsApp.png in the user's OneUILiquidGlass directory. These private artifacts are not committed. The log includes PR #113's cap=450/reserve=150 marker despite its historical filename. Detailed events stop after the budget is exhausted; missing events are not proof of missing native actions.

At 16:34:51.099 the stub is measured 7 ms after the recognized start. The draft is fully measured at 16:34:52.968; its backing is cleared only at 16:34:57.560. At 16:35:05.803 the draft disappears while the residual stub keeps the aggregate recorder predicate true. The log contains six starts and no end/normal-restored markers. TOP_CONTINUITY shows list=128 and an ancestor with 128 px padding despite status=0. Capture-generation growth is not an FPS or GPU measurement.

## Implemented invariants

- Native controls own footer measurement. The composer GlassPane keeps zero-sized layout parameters and measured dimensions; only its drawing bounds follow native screen coordinates mapped into footer coordinates. Native layout can reset drawing bounds, so reconciliation reinstates them before drawing. The recording envelope no longer derives its bottom from the decorated footer's height.
- Draft, usable editor, and stub are prioritized in that order. A visible enabled measured editor defeats a retained stub. Unknown/unmeasured routes retain the bounded bridge. Physical panel notifications always schedule one coalesced reconciliation, even without a new logical epoch.
- Returning to input restores voice decoration before measuring the quote envelope. Background removal/restoration preserves native padding. Quote coverage includes the quote rect and its structural backing chain; text/media/action leaves are retained.
- A draft-contained quote owns voice reply. Only distinct structurally identified duplicate previews are suppressed; vertical sorting no longer decides ownership. Native alpha is restored on exit.
- Mic and waveform discovery are scoped to the selected recorder. A mic far from the waveform is repositioned subject to timer, quote, action and control obstacles; no valid slot leaves native controls untouched.
- Only the message-list host grows into the known top system inset. Original margin and ancestor clipping are restored using the original host identity. Contact controls retain cutout-safe positioning; the Activity and IME are not translated.

## Review follow-ups

#108: the existing per-sample quote/mic reconciliation remains; physical acceptance is still pending.

#109: one canvas-owned factory no longer evicts/rearms another on the same row. A secondary factory is retained weakly and promoted after a complete native draw proves the incumbent absent. This deliberately retains one live capture owner per row; simultaneous independent live bubble and quote factories on one row are not implemented. Secondary factories retain native rendering. Bubble aesthetics remain deferred.

#113: capture-generation-only changes are sampled, but presentation success/failure and geometry/state changes bypass the short interval. The final 150 slots are reserved for actual mode changes and restoration, not geometry/footer samples or repeated visibility signals. The global and per-type caps still apply; the probe is not a lossless event recorder.

## Validation and remaining acceptance

Pure Java route tests cover initial stub, draft replacement, missing layout and five returns to an editor with a retained stub. Existing transition simulation exercises 20 cycles, delayed growth, bridge expiry, restoration, detach, mic collisions and capture scheduling. Probe regressions cover live-to-failed-to-live presentation within milliseconds, changed geometry and protection of semantic slots.

These tests do not execute WhatsApp or prove pixel composition. The implementation uses native layout evidence, not resolved version-specific recorder controller methods. It intentionally does not infer whether an exit was send or cancel, or whether a draft is paused, from visibility alone. Both exit operations share restoration; pause/resume keep following native draft geometry. Distinct semantic action attribution remains unimplemented pending a validated native mapping. No message/contact/media content is needed for that mapping.

Physical acceptance remains required for written/media reply, stationary recording start, repeated pause/resume, five cancel cycles with/without reply, send, IME changes, navigation, scrolling behind the header, and state isolation across roots. Record action-to-measurement, capture and actual presentation separately with monotonic time. No before/after main-thread/GPU percentiles or refresh-rate measurements were collected in this implementation environment. Do not claim the proposed 1–2-frame, 2 dp or 100 ms targets were met.

No merge or workflow dispatch is authorized. All installed repository workflows were checked to be manual-only before publication.

Validation: JDK 21 / Android SDK 36 local Java compilation passed. The full Android unit suite passed 508/508 after restoring the binary PGM fixtures byte-for-byte from Git (Windows autocrlf had expanded their headers; fixture expectations were not changed). Subsequent focused composer/recorder/ledger tests passed. `tools/test_conversation_glass.py`, `tools/test_glass_probe.py`, and `git diff --check` passed. `tools/test_app_only_runtime.py` with UTF-8 passed three tests but its unchanged SecurityHarness failed `forbidden operation accepted`; this separate security harness is not reported as green. Initial online Gradle dependency resolution failed DNS; cached offline resolution succeeded. No dependencies or test expectations were weakened.
