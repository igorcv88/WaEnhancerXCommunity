#!/usr/bin/env python3
"""Measure native PNG strips; never infer HWUI sigma from the requested blur radius.

Requires numpy and Pillow. Example (same crop for an unwarped vertical ESF):
  python tools/glass_optics_metrics.py raw.png soft.png --roi 300,90,200,8 --esf
Select straight, fully covered strips; use SHARP_ONLY to identify warping before interpreting MTF.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def linear_rgb(rgb):
    return np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)


def measure(path, roi, axis, esf=False, footprint=False):
    image = Image.open(path)
    if image.format != "PNG":
        raise ValueError(f"{path}: native PNG required (received {image.format})")
    rgba = np.asarray(image.convert("RGBA"), dtype=np.float64) / 255
    x, y, width, height = roi
    if min(x, y) < 0 or min(width, height) <= 0 or x + width > image.width or y + height > image.height:
        raise ValueError(f"{path}: ROI outside {image.width}x{image.height}")
    rgb = rgba[y:y + height, x:x + width, :3]
    luminance = linear_rgb(rgb) @ np.array([0.2126, 0.7152, 0.0722])
    profile = luminance.mean(axis=0 if axis == "x" else 1)
    report = {"file": str(path), "native_size": [image.width, image.height], "roi": roi,
              "axis": axis, "luminance_min": float(profile.min()),
              "luminance_max": float(profile.max()), "luminance_mean": float(profile.mean()),
              "profile_linear": profile.tolist()}
    if footprint:
        # Diagnostic scales: R=min(s_min,1), G=min(s_max/2,1), B=displacement/cap.
        # Only interpret a fully covered ROI, without native controls or a color transform.
        report["footprint_encoded_channel_median"] = {
            "s_min_clipped_at_1": float(np.median(rgb[:, :, 0])),
            "s_max_clipped_at_2": float(2 * np.median(rgb[:, :, 1])),
            "offset_over_cap": float(np.median(rgb[:, :, 2]))}
    if esf:
        end = max(1, len(profile) // 10)
        low, high = profile[:end].mean(), profile[-end:].mean()
        contrast = high - low
        if len(profile) < 16 or abs(contrast) < 0.02:
            raise ValueError(f"{path}: strip has no usable step edge; choose another ROI")
        edge = (profile - low) / contrast
        lsf = np.diff(edge)
        spectrum = np.abs(np.fft.rfft(lsf))
        dc = spectrum[0]
        if dc < 1e-9:
            raise ValueError(f"{path}: ESF DC is zero")
        mtf = spectrum / dc
        frequency = np.fft.rfftfreq(len(lsf))
        below = np.flatnonzero(mtf <= 0.5)
        report["edge"] = {"step_contrast_linear": float(abs(contrast)),
                          "negative_derivative_share": float(np.maximum(-lsf, 0).sum() / max(np.abs(lsf).sum(), 1e-9)),
                          "mtf50_first_bin_cycles_per_px": float(frequency[below[0]]) if len(below) else None,
                          "frequency_cycles_per_px": frequency.tolist(), "mtf": mtf.tolist(),
                          "assumption": "single step, flat end plateaus, locally invariant filter; warp/light can violate this"}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("png", nargs="+", type=Path)
    parser.add_argument("--roi", required=True, help="x,y,width,height in native pixels")
    parser.add_argument("--axis", choices=["x", "y"], default="x")
    parser.add_argument("--esf", action="store_true")
    parser.add_argument("--footprint", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    roi = list(map(int, args.roi.split(",")))
    if len(roi) != 4:
        parser.error("ROI must have four integers")
    results = [measure(p, roi, args.axis, args.esf, args.footprint) for p in args.png]
    serialized = json.dumps({"kind": "PNG measurement, not device benchmark", "results": results}, indent=2)
    if args.output:
        args.output.write_text(serialized + "\n", encoding="utf-8")
    else:
        print(serialized)


if __name__ == "__main__":
    main()
