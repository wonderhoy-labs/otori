#!/usr/bin/env python3
"""Walks the setup flow through the real Settings screens, tapping what a person would tap.

Emulator only. On a real phone these switches are for its owner to turn on.
Usage: onboarding_emulator.py --serial emulator-5554 --out DIR
"""
import argparse
import re
import subprocess
import time
from pathlib import Path


class UI:
    def __init__(self, serial, out):
        self.serial = serial
        self.out = out

    def adb(self, *args):
        return subprocess.run(["adb", "-s", self.serial, *args], capture_output=True).stdout

    def shell(self, cmd):
        return self.adb("shell", cmd).decode(errors="replace")

    def nodes(self):
        self.shell("uiautomator dump /sdcard/u.xml")
        xml = self.shell("cat /sdcard/u.xml")
        out = []
        for m in re.finditer(r'<node [^>]*?/?>', xml):
            n = m.group(0)
            text = re.search(r' text="([^"]*)"', n)
            desc = re.search(r' content-desc="([^"]*)"', n)
            bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            checked = re.search(r' checked="(\w+)"', n)
            enabled = re.search(r' enabled="(\w+)"', n)
            if bounds:
                out.append({
                    "text": (text.group(1) if text else "") or (desc.group(1) if desc else ""),
                    "x": (int(bounds.group(1)) + int(bounds.group(3))) // 2,
                    "y": (int(bounds.group(2)) + int(bounds.group(4))) // 2,
                    "checked": checked and checked.group(1) == "true",
                    "enabled": enabled and enabled.group(1) == "true",
                    "cls": re.search(r' class="([^"]*)"', n).group(1),
                })
        return out

    def tap_text(self, pattern, timeout=8.0):
        end = time.time() + timeout
        while time.time() < end:
            for n in self.nodes():
                if n["text"] and re.search(pattern, n["text"], re.I):
                    self.shell(f"input tap {n['x']} {n['y']}")
                    time.sleep(1.2)
                    return n
            time.sleep(0.5)
        return None

    def toggle(self):
        for n in self.nodes():
            if "Switch" in n["cls"]:
                return n
        return None

    def shot(self, name):
        (self.out / f"{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p"))

    def texts(self):
        return [n["text"] for n in self.nodes() if n["text"]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/setup-flow")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    ui = UI(a.serial, out)
    ui.shell("input keyevent KEYCODE_HOME")
    time.sleep(0.8)

    print("-- open the app")
    ui.shell("am start --activity-clear-top -n com.wonderhoylabs.otori/.MainActivity")
    time.sleep(1.5)
    ui.shot("1_app_before")
    print("screen:", ui.texts()[:6])

    print("-- accessibility")
    print("tap button:", bool(ui.tap_text(r"^Turn On$")))
    time.sleep(1.0)
    if not ui.toggle():
        print("list page, so tap Otori:", bool(ui.tap_text(r"^Otori$")))
        time.sleep(0.8)
    else:
        print("went straight to Island's own page")
    screen = ui.texts()
    print("screen:", screen[:10])
    restricted = any(re.search("restricted", t, re.I) for t in screen)
    print("restricted setting block:", restricted)
    ui.shot("2_accessibility_detail")
    sw = ui.toggle()
    print("toggle:", sw and {"checked": sw["checked"], "enabled": sw["enabled"]})
    if sw:
        ui.shell(f"input tap {sw['x']} {sw['y']}")
        time.sleep(1.2)
        ui.shot("3_accessibility_confirm")
        print("dialog:", ui.texts()[:8])
        print("tap Allow:", bool(ui.tap_text(r"^(Allow|OK|Turn on)$", 4)))
    time.sleep(1.5)
    print("enabled services:", ui.shell("settings get secure enabled_accessibility_services").strip())

    print("-- notification access")
    ui.shell("am start --activity-clear-top -n com.wonderhoylabs.otori/.MainActivity")
    time.sleep(1.2)
    print("tap button:", bool(ui.tap_text(r"^Turn On$")))
    time.sleep(1.0)
    if not ui.toggle():
        print("list page, so tap Otori:", bool(ui.tap_text(r"^Otori$")))
        time.sleep(0.8)
    else:
        print("went straight to Island's own page")
    screen = ui.texts()
    print("screen:", screen[:8])
    print("restricted setting block:", any(re.search("restricted", t, re.I) for t in screen))
    ui.shot("4_notification_detail")
    sw = ui.toggle()
    print("toggle:", sw and {"checked": sw["checked"], "enabled": sw["enabled"]})
    if sw:
        ui.shell(f"input tap {sw['x']} {sw['y']}")
        time.sleep(1.2)
        print("dialog:", ui.texts()[:8])
        print("tap Allow:", bool(ui.tap_text(r"^(Allow|OK|Turn on)$", 4)))
    time.sleep(1.2)
    listeners = ui.shell("settings get secure enabled_notification_listeners").strip()
    print("listener enabled:", "com.wonderhoylabs.otori" in listeners)

    ui.shell("am start --activity-clear-top -n com.wonderhoylabs.otori/.MainActivity")
    time.sleep(1.5)
    ui.shot("5_app_after")
    print("app now says:", ui.texts()[:4])


if __name__ == "__main__":
    main()
