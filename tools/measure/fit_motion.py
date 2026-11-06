#!/usr/bin/env python3
"""Fits damped springs to the island motion tracked from the real iOS island.

Usage: fit_motion.py ios_full.csv
Prints spring parameters (response, damping fraction, delay) for each animated property.
"""
import csv
import math
import sys

import numpy as np
from scipy.optimize import least_squares

rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(open(sys.argv[1]))]


def spring(t, x0, x1, response, damping, v0=0.0):
    """Closed form damped spring from x0 to x1, parameterised like SwiftUI's spring(response:dampingFraction:)."""
    w0 = 2 * math.pi / response
    t = np.maximum(t, 0)
    d = x0 - x1
    if damping < 1:
        wd = w0 * math.sqrt(1 - damping ** 2)
        a = damping * w0
        return x1 + np.exp(-a * t) * (d * np.cos(wd * t) + ((v0 + a * d) / wd) * np.sin(wd * t))
    return x1 + np.exp(-w0 * t) * (d + (v0 + w0 * d) * t)


def window(a, b):
    sel = [r for r in rows if a <= r["t"] <= b]
    return np.array([r["t"] for r in sel]), sel


def fit(name, t, y, x0, x1, guess=(0.55, 0.8, 0.0)):
    def res(p):
        return spring(t - p[2], x0, x1, p[0], p[1]) - y
    r = least_squares(res, guess, bounds=([0.1, 0.1, -0.1], [2.0, 1.5, 0.3]), loss="soft_l1", f_scale=2.0)
    rms = float(np.sqrt(np.mean(res(r.x) ** 2)))
    print(f"{name:26s} response={r.x[0]:.3f}s damping={r.x[1]:.3f} delay={r.x[2]*1000:4.0f}ms rms={rms:.2f}pt  ({x0:.1f}->{x1:.1f})")
    return r.x


print("== expand (compact -> expanded)")
t, s = window(19.50, 20.60)
t = t - 19.50
w = np.array([r["w_pt"] for r in s])
h = np.array([r["h_pt"] for r in s])
fit("width", t, w, 187.1, 370.5)
# Height readings glitch when the walk meets bright content mid-morph; soft_l1 keeps them from dominating.
fit("height", t, h, 36.9, 158.4)

print("== collapse (expanded -> compact)")
t, s = window(25.50, 26.60)
t = t - 25.50
w = np.array([r["w_pt"] for r in s])
h = np.array([r["h_pt"] for r in s])
top = np.array([r["top_pt"] for r in s])
for tt, ww, hh, tp in list(zip(t, w, h, top))[::4]:
    print(f"   {tt:5.3f} w={ww:6.1f} h={hh:6.1f} top={tp:5.2f}")


def collapse_w(p, t):
    r1, d1, delay, target1, switch, r2, d2 = p
    # Phase one shrinks towards target1; phase two retargets to the compact width at `switch`, inheriting
    # position and velocity.
    a = spring(t - delay, 370.5, target1, r1, d1)
    ts = max(switch - delay, 0)
    xs = float(spring(np.array([ts]), 370.5, target1, r1, d1)[0])
    eps = 1e-3
    vs = float((spring(np.array([ts + eps]), 370.5, target1, r1, d1)[0] - xs) / eps)
    b = spring(t - switch, xs, 187.1, r2, d2, vs)
    return np.where(t < switch, a, b)


def res(p):
    return collapse_w(p, t) - w


r = least_squares(res, (0.45, 1.0, 0.0, 126.0, 0.33, 0.45, 0.7),
                  bounds=([0.1, 0.3, -0.1, 80, 0.1, 0.1, 0.3], [1.2, 1.5, 0.2, 190, 0.7, 1.2, 1.5]),
                  loss="soft_l1", f_scale=2.0)
print("width two-phase: response1=%.3f damping1=%.3f delay=%.0fms target1=%.1f switch=%.3fs response2=%.3f damping2=%.3f rms=%.2fpt" % (
    r.x[0], r.x[1], r.x[2] * 1000, r.x[3], r.x[4], r.x[5], r.x[6], float(np.sqrt(np.mean(res(r.x) ** 2)))))
fit("height", t, h, 158.4, 36.9, (0.45, 1.0, 0.0))
fit("width single spring", t, w, 370.5, 187.1, (0.6, 0.45, 0.0))
