#!/usr/bin/env python3
"""End-to-end test of the island on a device, using real windows, a real MediaSession and real touches.

Usage: e2e.py --serial emulator-5554 [--out DIR]

Run tools/prepare_emulator.sh first: the device needs the app and the test player installed, with Otori's
accessibility service and notification listener enabled. Geometry is asserted against the measurements in the README.
"""
import argparse
import io
import re
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

PLAYER = "com.wonderhoylabs.otori.testplayer"
PLAYER_ACTIVITY = f"{PLAYER}/.PlayerActivity"
TITLES = ["Island Reference Track", "Second Track On The Queue"]
HOLE_DIAMETER_PX = 82.69  # From the cutout spec: 98 px on the 1280 px panel, scaled to 1080.
IDLE_PT = HOLE_DIAMETER_PX * 0.86 / (1080 / 393.0)
# The camera hole, plus the artwork on one side and the waveform on the other, with a 3 pt gap to each.
MIN_COMPACT_PT = 2 * (HOLE_DIAMETER_PX / 2 / (1080 / 393.0) + 3 + 8.8 + 22.6)
ICON_GAP_PX = 55  # The island keeps 21.7 pt, or 60 px, from the status bar items' bounds.


class Device:
    def __init__(self, serial, out):
        self.serial = serial
        self.out = out
        self.width, self.height = 1080, 2410
        self.pt = self.width / 393.0
        self.hole_cy = 86.06
        self.pill_top = self.hole_cy - 37.16 * self.pt / 2
        self.expanded_left = self.width / 2 - 371 * self.pt / 2

    def adb(self, *args, binary=False):
        out = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, check=False)
        return out.stdout if binary else out.stdout.decode(errors="replace")

    def shell(self, cmd):
        return self.adb("shell", cmd)

    def screenshot(self, name=None):
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        im = Image.open(io.BytesIO(png)).convert("RGB")
        if name:
            im.save(self.out / f"{name}.png")
        return im

    def tap(self, x, y):
        self.shell(f"input tap {x:.0f} {y:.0f}")

    def long_press(self, x, y, ms=700):
        self.shell(f"input swipe {x:.0f} {y:.0f} {x:.0f} {y:.0f} {ms}")

    def swipe(self, x0, y0, x1, y1, ms=180):
        self.shell(f"input swipe {x0:.0f} {y0:.0f} {x1:.0f} {y1:.0f} {ms}")

    def media_state(self):
        """Parses the test player's session from dumpsys: state name, position, title and custom action name."""
        dump = self.shell("dumpsys media_session")
        i = dump.find(f"package={PLAYER}")
        if i < 0:
            return None
        block = dump[i:i + 1600]
        state = re.search(r"state=PlaybackState \{state=(\w+)", block)
        pos = re.search(r"position=(\d+)", block)
        desc = re.search(r"description=([^,\n]*)", block)
        action = re.search(r"mName='?([^,']*)", block)
        return {
            "state": state.group(1) if state else None,
            "position": int(pos.group(1)) if pos else None,
            "title": desc.group(1).strip() if desc else None,
            "action": action.group(1) if action else None,
        }

    def focus(self):
        dump = self.shell("dumpsys window displays")
        m = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]*)\}", dump)
        if m:
            return m.group(1)
        dump = self.shell("dumpsys window")
        m = re.search(r"mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]*)\}", dump)
        return m.group(1) if m else ""


def measure(im, dev):
    """Bounding box of the island in pixels, from walking outward through the black region."""
    g = np.asarray(im.convert("L")).astype(int)
    cx = dev.width // 2
    col = g[:, cx]

    # The hole line is black in every state, so start there.
    seed = int(dev.hole_cy)
    top = seed
    while top > 0 and col[top - 1] < 90:
        top -= 1

    def run_end(line, start, step, bright=110, run=26):
        i, count = start, 0
        while 0 <= i < len(line):
            count = count + 1 if line[i] > bright else 0
            if count >= run:
                return i - step * run
            i += step
        return i

    bottom = run_end(col, seed, 1)
    h = bottom - top + 1
    if h > 80 * dev.pt:
        # Expanded: this row runs between the artwork and the time labels, which is pure black.
        row = g[top + int(h * 0.5)]
        left = run_end(row, cx, -1, 110, 8)
        right = run_end(row, cx, 1, 110, 8)
    else:
        # A pill. Artwork reaches the mid row, so measure near the top and correct for the capsule's curve.
        d = 10
        row = g[top + d]
        r = h / 2
        inset = r - np.sqrt(max(r * r - (r - d) * (r - d), 0))
        left = run_end(row, cx, -1, 150, 8) - inset
        right = run_end(row, cx, 1, 150, 8) + inset
    return {
        "left": float(left), "right": float(right), "top": int(top), "bottom": int(bottom),
        "w": float(right - left + 1), "h": int(h),
        "w_pt": float((right - left + 1) / dev.pt), "h_pt": float(h / dev.pt),
    }


def gaps_to_icons(im, b, dev):
    """Pixels from each end of the island to the nearest dark status bar pixel, or None if there isn't one."""
    g = np.asarray(im.convert("L")).astype(int)
    band = g[42:96]
    left = int(b["left"])
    right = int(b["right"])
    gap_l = gap_r = None
    for d in range(2, 240):
        if gap_l is None and left - d >= 0 and (band[:, left - d] < 120).any():
            gap_l = d
        if gap_r is None and right + d < dev.width and (band[:, right + d] < 120).any():
            gap_r = d
    return gap_l, gap_r


def check_compact_limits(r, name, b, dev):
    r.check(f"{name}: width is within its limits", MIN_COMPACT_PT - 1.5 <= b["w_pt"] <= 187.2 + 1.0,
            f"{b['w_pt']:.1f} pt, limits {MIN_COMPACT_PT:.1f} to 187.1")
    r.near(f"{name}: centred on the camera", (b["left"] + b["right"] + 1) / 2, dev.width / 2, 1.5, "px")


def check_compact(r, name, im, b, dev):
    """The island keeps its distance from the status bar's icons, unless it has already shrunk as far as it can."""
    check_compact_limits(r, name, b, dev)
    left, right = gaps_to_icons(im, b, dev)
    at_minimum = b["w_pt"] <= MIN_COMPACT_PT + 1.5
    clear = (left is None or left >= ICON_GAP_PX) and (right is None or right >= ICON_GAP_PX)
    r.check(f"{name}: keeps its distance from the status bar icons", at_minimum or clear,
            f"gap left {left} px, right {right} px, {b['w_pt']:.1f} pt wide")


class Report:
    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, name, ok, detail=""):
        print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""))
        if ok:
            self.passed += 1
        else:
            self.failed.append(name)

    def near(self, name, value, expected, tol, unit="pt"):
        self.check(name, abs(value - expected) <= tol, f"{value:.2f} {unit}, expected {expected:.2f} +/- {tol}")


def wait_for(predicate, timeout=6.0, interval=0.25):
    end = time.time() + timeout
    while time.time() < end:
        v = predicate()
        if v:
            return v
        time.sleep(interval)
    return predicate()


def settle(dev):
    """Waits until the island has stopped moving, so that slow devices don't fail on timing."""
    time.sleep(0.5)
    last = None
    end = time.time() + 6.0
    while time.time() < end:
        b = measure(dev.screenshot(), dev)
        now = (round(b["w_pt"]), round(b["h_pt"]))
        if now == last:
            return
        last = now
        time.sleep(0.35)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    dev = Device(a.serial, out)
    r = Report()
    dev.shell("input keyevent KEYCODE_WAKEUP")

    pill_cx = dev.width / 2
    pill_cy = dev.hole_cy
    m = lambda name: dev.expanded_left + name * dev.pt
    control_y = dev.pill_top + 157.2 * dev.pt

    print("== setup: no session")
    dev.shell(f"am force-stop {PLAYER}")
    dev.shell("am start -a android.settings.SETTINGS")
    time.sleep(2.5)

    print("== idle")
    im = dev.screenshot("01_idle")
    b = measure(im, dev)
    r.near("idle width", b["w_pt"], IDLE_PT, 1.0)
    r.near("idle height", b["h_pt"], IDLE_PT, 0.8)
    r.near("idle circle is centred on the camera hole (vertically)", (b["top"] + b["bottom"] + 1) / 2, dev.hole_cy, 1.5, "px")
    r.near("idle circle is centred on the camera hole (horizontally)", (b["left"] + b["right"] + 1) / 2, dev.width / 2, 1.5, "px")
    r.check("idle circle stays inside the hole, so no ring can show",
            b["w"] < HOLE_DIAMETER_PX and b["h"] < HOLE_DIAMETER_PX, f"{b['w']:.1f} x {b['h']} px in a {HOLE_DIAMETER_PX} px hole")
    a_img = np.asarray(im)
    r.check("idle circle is pure black", int(a_img[int(pill_cy), int(pill_cx)].sum()) == 0)
    r.check("nothing is drawn around the hole", int(a_img[int(pill_cy), int(pill_cx + 60)].sum()) > 300)

    print("== touches outside the island reach the app below")
    before = dev.focus()
    dev.tap(540, 900)
    after = wait_for(lambda: (lambda f: f if f and f != before else None)(dev.focus())) or ""
    dev.shell("input keyevent KEYCODE_BACK")
    wait_for(lambda: dev.focus() == before)
    r.check("a tap below the island opens the row under it", bool(after), f"focus {before} -> {after}")

    print("== starting playback in the foreground keeps the island idle")
    dev.shell(f"am start -n {PLAYER_ACTIVITY}")
    wait_for(lambda: (dev.media_state() or {}).get("state") == "PLAYING")
    settle(dev)
    b = measure(dev.screenshot("02_foreground_app"), dev)
    r.near("idle while the music app is in front", b["w_pt"], IDLE_PT, 1.5)
    s = dev.media_state()
    r.check("session is playing", s and s["state"] == "PLAYING", str(s))

    print("== compact")
    dev.shell("am start -a android.settings.SETTINGS")
    time.sleep(2.2)
    im = dev.screenshot("03_compact")
    b = measure(im, dev)
    check_compact(r, "compact", im, b, dev)
    r.near("compact height", b["h_pt"], 37.16, 0.8)
    roomy_width = b["w_pt"]
    a_img = np.asarray(im).astype(int)
    art_x = int(b["left"] + (8.8 + 11.3) * dev.pt)
    r.check("artwork is drawn", a_img[int(pill_cy), art_x].sum() > 60)
    tint_row = a_img[int(pill_cy), int(b["right"] - 25 * dev.pt):int(b["right"] - 8 * dev.pt)]
    r.check("waveform is drawn in a colour", (tint_row.max(axis=1) - tint_row.min(axis=1)).max() > 30)

    # Android decides how many icons to show, and sometimes merges these into one, so the island may not need to move.
    print("== more notifications: the island makes room for whatever icons Android shows")
    def settled_width(previous):
        """Waits for the island to react. It re-measures on each notification, and every 4 s regardless."""
        end = time.time() + 6.5
        while time.time() < end:
            w = measure(dev.screenshot(), dev)["w_pt"]
            if abs(w - previous) > 1.0:
                time.sleep(1.0)
                return
            time.sleep(0.4)

    dev.shell(f"am broadcast -n {PLAYER}/.NotifyReceiver --ei count 3")
    settled_width(roomy_width)
    im = dev.screenshot("03b_three_icons")
    b = measure(im, dev)
    check_compact(r, "with 3 notifications", im, b, dev)
    few_width = b["w_pt"]
    dev.shell(f"am broadcast -n {PLAYER}/.NotifyReceiver --ei count 14")
    settled_width(few_width)
    im = dev.screenshot("03c_many_icons")
    b = measure(im, dev)
    check_compact(r, "with 14 notifications", im, b, dev)
    a_img = np.asarray(im).astype(int)
    r.check("artwork still shows at the minimum", a_img[int(pill_cy), int(b["left"] + 20 * dev.pt)].sum() > 60)
    crowded_width = b["w_pt"]
    dev.shell(f"am broadcast -n {PLAYER}/.NotifyReceiver --ei count 0")
    settled_width(crowded_width)
    b = measure(dev.screenshot(), dev)
    r.near("island grows back when the icons go", b["w_pt"], roomy_width, 1.5)

    print("== waveform animates")
    a1 = np.asarray(dev.screenshot()).astype(int)[60:115, int(b["right"] - 26 * dev.pt):int(b["right"])]
    time.sleep(0.35)
    a2 = np.asarray(dev.screenshot()).astype(int)[60:115, int(b["right"] - 26 * dev.pt):int(b["right"])]
    r.check("waveform frames differ", np.abs(a1 - a2).sum() > 1500)

    print("== long press expands")
    dev.long_press(pill_cx, pill_cy)
    settle(dev)
    im = dev.screenshot("04_expanded")
    b = measure(im, dev)
    r.near("expanded width", b["w_pt"], 371, 2.0)
    r.near("expanded height", b["h_pt"], 200.6, 2.0)
    g = np.asarray(im.convert("L")).astype(int)
    r.check("drop shadow below the island", g[b["bottom"] + 8, 540] < g[b["bottom"] + 120, 540] - 8,
            f"{g[b['bottom'] + 8, 540]} vs {g[b['bottom'] + 120, 540]}")

    print("== transport controls drive the real session")
    dev.tap(m(185.3), control_y)
    r.check("pause reaches the session", wait_for(lambda: (dev.media_state() or {}).get("state") == "PAUSED"))
    time.sleep(0.5)
    dev.screenshot("05_paused")
    dev.tap(m(185.3), control_y)
    r.check("play reaches the session", wait_for(lambda: (dev.media_state() or {}).get("state") == "PLAYING"))

    dev.tap(m(255.5), control_y)
    r.check("next changes the track", wait_for(lambda: TITLES[1] in ((dev.media_state() or {}).get("title") or "")),
            str(dev.media_state()))
    time.sleep(0.5)
    dev.screenshot("06_next_track")
    dev.tap(m(114.6), control_y)
    r.check("previous changes the track back",
            wait_for(lambda: TITLES[0] in ((dev.media_state() or {}).get("title") or "")))

    print("== scrubbing seeks")
    bar_y = dev.pill_top + 113.9 * dev.pt
    dev.swipe(m(70), bar_y, m(181.75), bar_y, 400)
    time.sleep(0.5)
    s = dev.media_state()
    r.check("dragging the bar to the middle seeks there", s and 75_000 <= s["position"] <= 105_000, str(s))

    print("== collapse")
    dev.tap(540, 1500)
    settle(dev)
    b = measure(dev.screenshot("08_collapsed"), dev)
    r.near("a tap outside collapses to compact", b["w_pt"], roomy_width, 1.5)

    print("== swipe down on compact expands, swipe up collapses")
    dev.swipe(pill_cx, pill_cy, pill_cx, pill_cy + 110, 150)
    settle(dev)
    b = measure(dev.screenshot(), dev)
    r.near("swipe down expands", b["w_pt"], 371, 2.0)
    dev.swipe(pill_cx, dev.pill_top + 90 * dev.pt, pill_cx, dev.pill_top + 10 * dev.pt, 150)
    settle(dev)
    b = measure(dev.screenshot(), dev)
    r.near("swipe up collapses", b["w_pt"], roomy_width, 1.5)

    print("== swipe aside tucks the activity away, and brings it back")
    dev.swipe(pill_cx, pill_cy, pill_cx + 230, pill_cy, 150)
    settle(dev)
    b = measure(dev.screenshot("085_swiped_aside"), dev)
    r.near("swipe aside on compact returns to idle", b["w_pt"], IDLE_PT, 1.5)
    dev.swipe(pill_cx, pill_cy, pill_cx + 230, pill_cy, 150)
    settle(dev)
    b = measure(dev.screenshot(), dev)
    r.near("swipe aside on idle brings it back", b["w_pt"], roomy_width, 1.5)

    print("== tap on compact opens the music app")
    dev.tap(pill_cx, pill_cy)
    opened = wait_for(lambda: PLAYER in dev.focus())
    r.check("tap opens the app", bool(opened), dev.focus())
    settle(dev)
    b = measure(dev.screenshot("09_opened_app"), dev)
    r.near("island goes idle when the app comes to the front", b["w_pt"], IDLE_PT, 1.5)

    print("== paused media leaves after the linger, ended sessions leave at once")
    dev.shell("am start -a android.settings.SETTINGS")
    time.sleep(2)
    b = measure(dev.screenshot(), dev)
    r.near("compact again after leaving the app", b["w_pt"], roomy_width, 1.5)
    dev.shell(f"am force-stop {PLAYER}")
    time.sleep(2.2)
    b = measure(dev.screenshot("10_session_ended"), dev)
    r.near("session ending returns to idle", b["w_pt"], IDLE_PT, 1.5)

    print("== at rest the island takes no touches, so the status bar works right over the camera")
    win = dev.shell("dumpsys window windows | grep -A12 'Window #.*OtoriTouch' | grep -m1 -E 'fl=|flags='")
    r.check("touch window is not touchable when idle", "NOT_TOUCHABLE" in win, win.strip()[:90])
    before = dev.shell("dumpsys window | grep -m1 mCurrentFocus")
    r.check("the shade is closed before the swipe", "NotificationShade" not in before, before.strip()[:70])
    dev.swipe(pill_cx, pill_cy, pill_cx, 1400, 600)
    time.sleep(1.3)
    dev.screenshot("11_shade_from_camera")
    focus = dev.shell("dumpsys window | grep -m1 mCurrentFocus")
    r.check("swiping down from the camera opens the shade", "NotificationShade" in focus, focus.strip()[:70])
    dev.shell("cmd statusbar collapse")
    time.sleep(0.8)

    crashes = dev.adb("logcat", "-d", "-b", "crash")
    r.check("no crashes from the app", "com.wonderhoylabs.otori" not in crashes)

    print(f"\n{r.passed} passed, {len(r.failed)} failed")
    for f in r.failed:
        print("  failed:", f)
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
