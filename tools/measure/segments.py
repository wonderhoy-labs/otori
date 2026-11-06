#!/usr/bin/env python3
"""Lists motion segments in a tracked capture."""
import csv
import sys

rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(open(sys.argv[1]))]
segs = []
cur = None
for a, b in zip(rows, rows[1:]):
    if abs(a["w_pt"] - b["w_pt"]) > 0.25 or abs(a["h_pt"] - b["h_pt"]) > 0.25:
        if cur is None or a["t"] - cur[1] > 0.3:
            cur = [a["t"], b["t"]]
            segs.append(cur)
        else:
            cur[1] = b["t"]
at = lambda t: min(rows, key=lambda r: abs(r["t"] - t))
for i, s in enumerate(segs):
    r0, r1 = at(s[0] - 0.06), at(s[1] + 0.06)
    print(f"{i} t={s[0]:.2f}..{s[1]:.2f} ({s[1]-s[0]:.2f}s) "
          f"w {r0['w_pt']:.1f}->{r1['w_pt']:.1f} h {r0['h_pt']:.1f}->{r1['h_pt']:.1f} "
          f"top {r0['top_pt']:.1f}->{r1['top_pt']:.1f} r {r0['radius_pt']:.1f}->{r1['radius_pt']:.1f}")
