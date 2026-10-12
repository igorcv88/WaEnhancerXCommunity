# Optical reconstruction and S25 Ultra acceptance

Specification: the user's `LAUDO_DEFINITIVO_OTICA_LIQUID_GLASS_WAENHANCERX_2026-10-09.md`.
Base: `master@cbbf20205a6da6ee2bffda2879d1c8245bca1b1e`. Historical handoff entries are not the current specification.

## Enable the candidate

Liquid Glass settings → **Apply reconstruction candidate (Clear off)**. This explicitly enables
the corrected renderer, Filtering, Adaptive contrast, Colour and Temporal groups, selects
`reconstruct`, resets diagnostics and displacement, and disables Clear. It does not change the
material style, opacity or surface switches: use LIQUID with Headers, Input Bar and Floating Nav
enabled, and **Search disabled**, matching the report. Return to WhatsApp to apply without restart.

Existing installations keep the baseline profile until a candidate is selected. This avoids
silently replacing the floating bar's established finish or a user's optical configuration.
The floating bar's BlurView/View path remains on its baseline transfer and finishing regardless
of the selected app-pane profile. Its existing diagnostics still apply, but it has no new
capture-generation overlay. App panes and material drawables use the selected profile.

## What the candidate changes

All source selection, 1:1 display-list recording, transforms, acyclicity guards, recovery,
native controls, wallpaper underlay and damage-driven scheduling remain in their existing paths.
No extra source tree, shared-window capture or software snapshot is introduced.

| Stage | Baseline / balanced / strong | Reconstruction |
|---|---|---|
| Residual-detail input | Raw recorded content | Platform prefilter, requested radius `clamp(0.65*density,0.75,3)` px |
| Soft input | Platform blur | Same requested radius as baseline |
| Geometry | Existing bounded V2 warp | Same V2 warp, including cap and per-channel bounds |
| Transfer | Rim/full-depth: `.15/.65`, `.45/.45`, `.65/.35` | `.65/.40`; smooth depth bias up to `.12` according to normalized displacement |
| Reconstruction | One sample per channel | Five weighted native-resolution samples along the finite-difference Jacobian columns; collinear channel samples reused when dispersion is absent |
| Composition | Each material branch is encoded before weighted PLUS | Linear numeric, premultiplied branch contributions; PLUS; one finishing/encoding stage |
| Volume | Historical perimeter-patch lighting | Fixed directional micro-highlight, broader reflection and trailing internal occlusion; coherent with the outline |
| Contrast | Historical per-branch protection | Composed linear pixel, after lighting, protected once |

The five-tap footprint is a bounded quadrature approximation, **not EWA, a mip pyramid or
measured Apple optics**. The detail prefilter suppresses texture that would otherwise be
magnified; the footprint addresses pixel integration under minification. The geometric field
itself is unchanged, so filtering is evaluated without increasing displacement toward a fold.
The finishing stage unpremultiplies the mixture and applies shape coverage once. No additional
external drop shadow is installed; the candidate's volume change is reflection/internal occlusion.

Android `createBlurEffect` receives a **radius**. The historical `sigmaPx` method name remains
for source compatibility, but no known HWUI Gaussian sigma/MTF is claimed. A three-requested-radius
margin remains a conservative support policy to be checked on the actual GPU.

## Diagnostics and real graphical PNGs

The Developer diagnostics selector is available in the same APK. `NONE` is `FINAL`.

| Mode | Output / interpretation |
|---|---|
| RAW_INPUT | Recorded backdrop, no warp/filter/material, within the shape mask |
| SHARP_ONLY | Raw backdrop with V2 warp; no material or sparse legacy blur |
| SOFT_ONLY | Platform-filtered, warped input; no material. Works even with Filtering off; zero-radius material gives its unfiltered limit |
| BETA_HEATMAP | Red = soft share, green = residual detail share |
| JACOBIAN | Green = singular minimum ≥ .30, yellow below, red determinant ≤ 0; diagnostic, not a numeric GPU benchmark |
| FOOTPRINT | R = minimum singular value clipped at 1; G = maximum singular value divided by 2, clipped; B = displacement/cap. Reports geometry for both fixed filtering scales, not an automatic LOD pyramid |
| COVERAGE | Shape alpha/SDF coverage as greyscale |
| PROTECTION | Red = applied protection/cap, green = beta. Reconstruction measures the final composed pixel; baseline measures the historical soft branch |
| FINAL_NO_LIGHT | Material/tint/contrast with highlights and internal shadow removed |
| TIME_GENERATION | App panes/drawables overlay capture generation, age in uptime ms and effect-rebuild count, without message data |
| GRID / DISPLACEMENT / LEGACY_WARP | Existing synthetic warp and historical diagnostics; legacy warp remains diagnostic only |

The in-app **synthetic GPU lab** uses detached RenderNodes and the same LensEffect graph,
with 1/2/4/8-pixel bars, a diagonal, a black/white step and colour fields. It always runs the
corrected renderer, with the current optical groups/profile/debug settings, even if the master
switch is off in WhatsApp. It uses a **dark LIQUID calibration material**; light-theme legibility
must additionally be checked on actual app surfaces. API 33+ and a complete hardware frame are
required. The lab has no timer or animation loop and never captures WhatsApp.

**Export synthetic GPU PNG** invokes PixelCopy on the fully visible lab rectangle. RAW/SHARP/SOFT
therefore produce actual isolated graphical outputs of a known scene rather than a Java simulation.
The PNG contains the window-composed lab, including its pattern around the mask; it is not a
dump of private driver textures. The private cached PNG survives configuration changes while
the document picker is open. Choose the PNG destination, and also **Copy lab parameters**:
profile/groups, physical width/height, density, API, device, module version, generation, requested
radii and effect status. Keep those parameters with each PNG. A failed/unrendered copy reports
an error, never a software image labelled GPU evidence.

For actual Headers/Input Bar, use the debug modes and native `screencap -p`; the synthetic lab
cannot establish that the WhatsApp capture source is correctly aligned or timely.

## Compact device matrix

Keep resolution/density, wallpaper, opacity, display scale, brightness, theme, scene and scroll
position constant. Record module/WhatsApp build and profile/debug/clear/group settings.

| Test | Change | Capture / decision |
|---|---|---|
| A0 | Baseline, FINAL, Clear off | Native PNG reproducing center-soft/rim-sharp and 30–60 s controlled scroll |
| A1 | Balanced, then strong; no other change | Same PNG; inspect line-width/contrast continuity over the rim |
| A2 | SOFT_ONLY with Clear off/on, in a flat central region | Measure platform ESF there, where warp and dispersion vanish; Clear changes other parameters and is not a full-surface blur-only comparison |
| A3 | Reconstruction vs strong; SHARP/SOFT/FOOTPRINT in each | Check pixel stretching, diagonals and corners; distinguish same geometric warp from new reconstruction |
| A4 | Reconstruction FINAL_NO_LIGHT vs FINAL | Judge volume/reflection/occlusion with optics/capture fixed |
| A5 | RAW_INPUT on header/input vs floating bar | Compare source coverage/alignment and capture timing; bar input is already BlurView-filtered and is not the same raw texture |
| A6 | Reconstruction selected; glass OFF / baseline / candidate | Same 30–60 s scroll and idle; frame p50/p90/p95/p99, misses, GPU timing and allocations |

In the lab export RAW, SHARP, SOFT, BETA, FOOTPRINT, COVERAGE, NO_LIGHT and FINAL at the same size.
For a clean step strip, use `tools/glass_optics_metrics.py` (numpy/Pillow):

```sh
python tools/glass_optics_metrics.py raw.png soft.png --roi 300,90,200,8 --esf --output optical.json
```

Coordinates are examples; select a fully covered, straight edge strip in your PNG. The tool
rejects non-PNG/out-of-bounds/no-step inputs, measures linear luminance and finite-difference
LSF/MTF, and reports negative derivative share. Warp, rim illumination and non-flat plateaus
violate stationary ESF assumptions; inspect SHARP_ONLY before interpreting MTF. No blur sigma is
estimated from radius. Use `--footprint` only on a fully covered diagnostic strip without controls.
Existing `tools/glass_profile.py` remains the frame-stat analysis helper.

## Stability and cost acceptance

Repeat home → chats → home, keyboard open/close, audio input, reply preview, menus, fast scroll,
theme/scale/rotation where supported, Battery Saver, source-late recovery and glass disable/restore.
No stale conversation pixels, capture cycle, fallback flicker, black hole, opaque native fill or
new text/hint/icon contrast regression is acceptable. Search stays off for baseline reproduction.

Reconstruction charges **6 padded pixel-passes** in LiveBudget versus historical 3 (conservative
blend-inclusive work proxy). It adds one small prefilter and one finishing pass; footprint and
chromatic work concentrate near the rim, and beta=1 skips the detail branch in the body. The
source is recorded once; shaders are retained and effects rebuild only for changed keys/state.
This is **not measured GPU cost or battery improvement**. Profile selection changes admission
cost; test limited-budget combinations too. Sharing capture/pyramids is deferred until device
evidence justifies source/temporal changes to the stable infrastructure.

CPU PGM golden images under `app/src/test/resources/optics` cover the native-pixel geometry,
profile transfer and a five-sample ideal-Gaussian reference. They intentionally omit actual
HWUI filtering, colour transforms, finishing and window composition. JVM tests and translated
GLSL syntax checks cannot validate Android's AGSL compiler/driver. Acceptance of the final
appearance, colour/alpha, PSF, GPU time, frame pacing and energy remains a device task.
