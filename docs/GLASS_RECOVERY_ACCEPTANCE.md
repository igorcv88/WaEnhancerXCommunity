# Liquid Glass recovery acceptance

Compare `master@cd198492` (A) with `fix/glass-session-recovery` (B). Use the same WhatsApp build, conversation, wallpaper, module preferences and optical profile. Start with V2 and all four groups enabled; keep Clear profile unchanged between A/B. This patch preserves shader programs and profile defaults.

## Build and local tests

Use JDK 21, Android SDK 36 and the existing dependencies. Run:

```sh
./gradlew :app:testWhatsappDebugUnitTest :app:assembleWhatsappDebug
```

No GitHub workflow is needed or authorized by these instructions. Check package/version/signing before installing the branch build. A debug APK may require a different installation procedure from a signed release; do not clear module settings as part of an A/B comparison.

## Physical checks

| Scenario | Repetitions | Expected behavior |
|---|---:|---|
| Home → conversation → Home | 10 | Glass recovers without a manual restart. A surviving Home root retains session/binding identity across stop/resume. A Conversation genuinely destroyed by WhatsApp may legitimately rebuild. |
| Switch between two chats with visibly different wallpaper/messages | 10 | No frame of the previous chat is intentionally retained after a source/visibility epoch changes. |
| Keyboard open/close; empty input → text → empty | 10 | Capsule ends before the action disc, with no rim underneath it; native icons, typing and touch behavior remain intact. |
| Voice recording/cancel, reply preview and multiline input | 5 each | Correct shape/visibility and no new hit targets or clipped native foreground. |
| Battery Saver OFF → ON → OFF | 5 | Same optical profile while APIs remain usable; no policy-triggered switch to a solid accent fill. |
| Screenshot, recents, camera/contact popup, then return | 5 each | Lost display lists recapture; no persistent fallback or Activity leak. |
| Disable/re-enable header/composer | 5 | Native layout/background/padding restore correctly; material can be installed again. |
| Scroll with a high-contrast image under header/composer | 5 | Header has one rounded optical contour; outside it is plain wallpaper continuity. Backdrop stays synchronized. |

Choose the two-piece composer/action-disc design already recommended by the report. The capsule uses an actual measured disc rect; native disc foreground and click handling remain owned by WhatsApp. Check both layout directions if available. A transparent native action animation or a renamed view requires a device-specific adjustment, not an invented geometry measurement.

## Diagnostics

Filter `WaEnhancerX/GlassState`, `WaEnhancerX/LiveGlass`, `WaEnhancerX/LensV2` and `[LiquidGlass/App]`. `GlassState` logs transitions and changes in live/fallback presentation, rather than every successful frame. IDs are process-local opaque identities, not chat/contact identifiers.

After `activity-stop`, expect `SUSPENDED`, not a session release/restored native background. On return to the same root, expect `REVALIDATING` and successful capture/presentation. `destroy-or-detach` is a legitimate release. Check `source-readiness-changed`, `source-changed`, `material-changed`, `display-list-lost`, `capture-exception`, `capture-not-ready` and budget refusals separately. Capture generation counts recordings; rebuild count counts effect installation. Neither measures GPU time, displayed pixels or watts.

A failed attempt displays neutral fallback; this implementation does not freeze previous chat pixels. After three transient failures, retry stops until a relevant event. Shader/linkage capability failure is separate. Verify that a later successful capture returns presentation to live in the same binding. A screenshot can drop a node after pre-draw; the fallback can last until the next valid capture, and zero fallback frames in that case is not claimed.

Collect a short post-patch trace only if a physical check fails; include the APK identity and timestamps. The earlier navigation trace had a stable WhatsApp PID and Battery Saver OFF, so neither a process crash nor Battery Saver is established as its cause.

## Performance and energy

Compare equivalent A/B/A/B scenes after warm-up with the same brightness, refresh rate, network, charging state and temperature. Use interval-specific FrameTimeline p50/p95/p99 and capture/rebuild counters. Report actual GPU/energy measurements separately. Removing the optical header band removes its logical pixel-pass cost; it does not establish a percentage reduction in GPU time or battery consumption. Complete ROI damage tracking and cross-provider shared capture remain future profiling work.
