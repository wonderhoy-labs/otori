#!/usr/bin/env python3
"""Compares island motion tracked on Android with the real iOS island, on the same metric.

Usage: compare_motion.py tools/measure/ios_full.csv android.csv
Finds each Android transition, slides it in time onto the iOS curve, and reports the remaining error.
Frames where the iOS tracker hit bright content (height glitches, compact-art width glitches) are left out.
"""
import csv
import sys

import numpy as np


def load(path):
    rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(open(path))]
    return np.array([r["t"] for r in rows]), np.array([r["w_pt"] for r in rows]), \
        np.array([r["h_pt"] for r in rows]), np.array([r["top_pt"] for r in rows])


def first_crossing(t, y, start, end, level, rising):
    for a, v in zip(t, y):
        if a < start or a > end:
            continue
        if (rising and v >= level) or (not rising and v <= level):
            return a
    return None


def compare(name, ios, android, ios_window, android_window, level, rising, span):
    it, iw, ih, _ = ios
    at, aw, ah, _ = android
    t0_i = first_crossing(it, iw, *ios_window, level, rising)
    t0_a = first_crossing(at, aw, *android_window, level, rising)
    if t0_i is None or t0_a is None:
        print(f"{name}: no transition found")
        return
    best = (1e9, 0.0)
    # Refine the alignment; only the transition's start time is free, not its shape.
    grid = np.arange(0, span, 0.012)
    for shift in np.arange(-0.08, 0.08, 0.002):
        pa = np.interp(grid + t0_a + shift, at, aw)
        pi = np.interp(grid + t0_i, it, iw)
        err = np.sqrt(np.mean((pa - pi) ** 2))
        if err < best[0]:
            best = (err, shift)
    shift = best[1]
    pa_w = np.interp(grid + t0_a + shift, at, aw)
    pi_w = np.interp(grid + t0_i, it, iw)
    pa_h = np.interp(grid + t0_a + shift, at, ah)
    pi_h = np.interp(grid + t0_i, it, ih)
    # The iOS height reading glitches where its walk meets bright content, so outliers are dropped.
    # Heights differ in their end point (the media island is taller than the Live Activity it was measured
    # on), so the shapes are compared as fractions of their own travel.
    def fraction(h):
        lo, hi = 37.16, max(h.max(), 38.0)
        return (h - lo) / (hi - lo)
    df = fraction(pa_h) - fraction(pi_h)
    good = np.abs(df) < np.percentile(np.abs(df), 85) + 1e-6
    print(f"{name}: width RMS {best[0]:.2f} pt, height shape RMS {np.sqrt(np.mean(df[good] ** 2)) * 100:.1f}% of travel")
    print("   t(s)   iOS w   And w   |  iOS h   And h")
    for g, a1, b1, a2, b2 in list(zip(grid, pi_w, pa_w, pi_h, pa_h))[::max(1, len(grid) // 14)]:
        print(f"   {g:4.2f}  {a1:6.1f}  {b1:6.1f}   | {a2:6.1f}  {b2:6.1f}")


def main():
    ios = load(sys.argv[1])
    android = load(sys.argv[2])
    at, aw, _, _ = android
    rising = []
    falling = []
    prev = aw[0]
    for t, w in zip(at, aw):
        if prev < 230 <= w:
            rising.append(t)
        if prev > 300 >= w:
            falling.append(t)
        prev = w
    print("android transitions: expand at", [round(x, 2) for x in rising], "collapse at", [round(x, 2) for x in falling])
    if rising:
        compare("EXPAND", ios, android, (19.4, 20.4), (rising[0] - 0.5, rising[0] + 0.5), 230, True, 0.85)
    if falling:
        compare("COLLAPSE", ios, android, (25.4, 26.4), (falling[0] - 0.5, falling[0] + 0.5), 300, False, 0.64)


if __name__ == "__main__":
    main()
