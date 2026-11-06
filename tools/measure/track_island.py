#!/usr/bin/env python3
"""Sub-pixel tracker for the island silhouette in a screen capture.

Usage: track_island.py in.mp4 out.csv --crop W:H:X:Y --ppp PX_PER_POINT [--top-offset PX]

The island is the black shape on a lighter background. Edges come from walking in from the screen sides at
mid-height, and are refined with the antialiased edge pixel's coverage. Corner radius comes from how fast the
silhouette narrows towards the top edge.
"""
import argparse
import csv
import json
import subprocess

import numpy as np

SIDE = 185  # grey level that counts as background beside the island


def frame_times(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "frame=pts_time", "-of", "json", path])
    return [float(f["pts_time"]) for f in json.loads(out)["frames"]]


def walk_out(line, start, step, bright, run):
    """Walks from `start` until `run` consecutive background pixels, returning the sub-pixel island edge.

    Island content may be bright, but only background forms a long bright run this close to the island.
    """
    i = start
    n = len(line)
    count = 0
    while 0 <= i < n:
        count = count + 1 if line[i] >= bright else 0
        if count >= run:
            first_bg = i - step * (run - 1)
            edge_px = first_bg - step  # innermost pixel that may be partly island
            ref = first_bg + step * 3
            bg = float(line[ref]) if 0 <= ref < n else float(line[first_bg])
            cover = sum(1.0 - min(float(line[p]) / max(bg, 1.0), 1.0) for p in (edge_px, first_bg))
            return edge_px + cover if step > 0 else edge_px + 1 - cover
        i += step
    return None


def inward(line, step, dark):
    """Walks in from the screen edge to the first island pixel, returning the sub-pixel boundary."""
    n = len(line)
    i = 3 if step > 0 else n - 4  # the outermost pixels are the partly covered screen edge
    while 0 <= i < n and line[i] >= dark:
        i += step
    if not 0 <= i < n:
        return None
    outer = i - step
    ref = i - step * 4
    bg = float(line[ref]) if 0 <= ref < n else 240.0
    cover = sum(1.0 - min(float(line[p]) / max(bg, 1.0), 1.0) for p in (outer, ) if 0 <= p < n)
    return i - cover if step > 0 else i + 1 + cover


def measure(g, bright, cx, run, ppp):
    col = g[:, cx]
    dark_rows = np.where((g[:, cx - 30:cx + 31] < 40).mean(axis=1) > 0.5)[0]
    dark_rows = dark_rows[dark_rows > 4]
    if len(dark_rows) == 0:
        return None
    seed = int(dark_rows[0]) + 4
    top = walk_out(col, seed, -1, bright, 6)
    # The drop shadow darkens the background below the island, so the bottom uses a lower level and a run
    # longer than any stem in the content.
    bottom = walk_out(col, seed, 1, 70, 26)
    if top is None or bottom is None:
        return None
    mid = int((top + bottom) / 2)
    if mid / ppp >= 52:
        # Below the status bar the sides can be found from outside, which is immune to bright content.
        left = inward(g[mid], 1, 40)
        right = inward(g[mid], -1, 40)
    else:
        left = walk_out(g[mid], cx, -1, SIDE, run)
        right = walk_out(g[mid], cx, 1, SIDE, run)
    if left is None or right is None:
        return None
    radii = []
    for d in (2, 3, 4, 5, 6, 8, 10, 14):
        y = int(top) + d
        if y >= bottom:
            break
        l2 = walk_out(g[y], cx, -1, SIDE, run)
        r2 = walk_out(g[y], cx, 1, SIDE, run)
        if l2 is None or r2 is None:
            continue
        inset = ((l2 - left) + (right - r2)) / 2.0
        dy = y + 0.5 - top
        if inset > 0.5:
            # Circle through (inset, dy): r = (inset^2 + dy^2) / (2 dy)
            radii.append((inset * inset + dy * dy) / (2.0 * dy))
    radius = float(np.median(radii)) if radii else 0.0
    return left, right, top, bottom, radius


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--crop", required=True)
    ap.add_argument("--ppp", type=float, default=1.0)
    ap.add_argument("--bright", type=int, default=190, help="grey level that counts as background")
    ap.add_argument("--run", type=int, default=9, help="consecutive background pixels that end the island")
    ap.add_argument("--top-offset", type=float, default=0.0, help="crop rows above the screen's top edge")
    ap.add_argument("--side", type=int, default=185)
    a = ap.parse_args()
    global SIDE
    SIDE = a.side
    w, h, x, y = map(int, a.crop.split(":"))
    times = frame_times(a.input)
    proc = subprocess.Popen([
        "ffmpeg", "-v", "error", "-nostdin", "-i", a.input, "-vf", f"crop={w}:{h}:{x}:{y},format=gray",
        "-fps_mode", "passthrough", "-f", "rawvideo", "-"], stdout=subprocess.PIPE)
    cx = w // 2
    t0 = times[0]
    n = 0
    with open(a.output, "w", newline="") as f:
        wr = csv.writer(f, lineterminator="\n")
        wr.writerow(["frame", "t", "w_pt", "h_pt", "top_pt", "cx_pt", "radius_pt"])
        for i, t in enumerate(times):
            buf = proc.stdout.read(w * h)
            if len(buf) < w * h:
                break
            g = np.frombuffer(buf, np.uint8).reshape(h, w)
            m = measure(g, a.bright, cx, a.run, a.ppp)
            if m is None:
                continue
            l, r, top, b, rad = m
            wr.writerow([i, round(t - t0, 4), round((r - l) / a.ppp, 2), round((b - top) / a.ppp, 2),
                         round((top - a.top_offset) / a.ppp, 2), round(((l + r) / 2) / a.ppp, 2),
                         round(rad / a.ppp, 2)])
            n += 1
    proc.kill()
    print(f"tracked {n} frames -> {a.output}")


if __name__ == "__main__":
    main()
