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
| Three or more transient failures, then stable source | 5 | Capture recovers automatically in the same binding, with no resume/layout requirement. |
| One admitted surface plus hidden/budget-refused candidates at rest | 5 | No sustained capture/redraw loop; logical recording counters settle after any trailing frame. |
| Old conversation Activity destroyed after a newer same-class instance resumes | 5 | Name lookup and resumed state continue to refer to the newer instance. |
| Scroll with a high-contrast image under header/composer | 5 | Header has one rounded optical contour; outside it is plain wallpaper continuity. Backdrop stays synchronized. |

Choose the two-piece composer/action-disc design already recommended by the report. The capsule uses an actual measured disc rect; native disc foreground and click handling remain owned by WhatsApp. Check both layout directions if available. A transparent native action animation or a renamed view requires a device-specific adjustment, not an invented geometry measurement.

## Diagnostics

Filter `WaEnhancerX/GlassState`, `WaEnhancerX/LiveGlass`, `WaEnhancerX/LensV2` and `[LiquidGlass/App]`. `GlassState` logs transitions and changes in live/fallback presentation, rather than every successful frame. IDs are process-local opaque identities, not chat/contact identifiers.

After `activity-stop`, expect `SUSPENDED`, not a session release/restored native background. On return to the same root, expect `REVALIDATING` and successful capture/presentation. `destroy-or-detach` is a legitimate release. Check `source-readiness-changed`, `source-changed`, `material-changed`, `display-list-lost`, `capture-exception`, `capture-not-ready` and budget refusals separately. Capture generation counts recordings; rebuild count counts effect installation. Neither measures GPU time, displayed pixels or watts.

A failed attempt displays neutral fallback; this implementation does not freeze previous chat pixels. After three transient failures, expect `BACKOFF`, followed by a later attempt after 1/2/4/5 seconds (maximum 5 seconds), without navigating or restarting. Stop/destroy must cancel retry callbacks. Shader/linkage capability failure stays `DISABLED` and is separate. A hidden, intentionally released, budget-refused or uninitialized surface must not generate an immediate dropped-recording redraw. Verify that a later successful capture returns presentation to live in the same binding. A screenshot can drop a node after pre-draw; the fallback can last until the next valid capture, and zero fallback frames in that case is not claimed.

For the header, `CAPTURE_WALLPAPER_ONLY` / `SOURCE_UNAVAILABLE` must remain waiting/degraded/backoff while a required safe message source is missing. A recursive coordinator must still be refused. Once the safe list appears, the same capsule must reach `CAPTURE_LIVE_CONTENT` and `LIVE`, including when the list dimensions never change.

In a resumed root, correlate `RESUMED` / `resumeEpoch` with each surface’s `FIRST_PRESENTATION`, `FIRST_COMPLETE_CAPTURE` and `PRESENTING_LIVE reason=first-after-resume`. Fields `firstPresentationMs`, `firstCompleteCaptureMs`, `firstLiveMs` and `fallbackFrames` measure different events; `-1` means not observed yet. Frames are counted per root pre-draw and deduplicated per surface, excluding nested capture draws. They describe app draw submissions, not SurfaceFlinger confirmation; use FrameTimeline/video for actual display timing. Do not treat a live header as evidence that all window surfaces are live.

Temporary fallback has neutral 18–32% tint; permanent renderer loss keeps the static material’s 58% opacity floor. Check message text, toolbar titles, input hints and native icons over light, dark and high-contrast wallpapers before accepting these provisional tint bounds. Neither a zero-flicker transition nor device-verified legibility is claimed by JVM tests.

Collect a short post-patch trace only if a physical check fails; include the APK identity and timestamps. The earlier navigation trace had a stable WhatsApp PID and Battery Saver OFF, so neither a process crash nor Battery Saver is established as its cause.

## Performance and energy

Compare equivalent A/B/A/B scenes after warm-up with the same brightness, refresh rate, network, charging state and temperature. Use interval-specific FrameTimeline p50/p95/p99 and capture/rebuild counters. Report actual GPU/energy measurements separately. Removing the optical header band removes its logical pixel-pass cost; it does not establish a percentage reduction in GPU time or battery consumption. Complete ROI damage tracking and cross-provider shared capture remain future profiling work.

Measure the former optical band and the plain wallpaper continuity separately in a static scene and during the same controlled scroll. Idle counters must settle with one admitted surface plus hidden/budget-refused candidates. Compare GPU/frame time directly; do not convert logical pixel-pass savings into a GPU-time or battery percentage.
