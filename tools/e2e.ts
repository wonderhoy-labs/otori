// End-to-end test of the island on a device, using real windows, a real MediaSession and real touches.
//
//   pnpm e2e --serial emulator-5554 [--out DIR]
//
// Run `pnpm prepare-emulator emulator-5554` first: the device needs the app and the test player installed, with
// Otori's accessibility service and notification listener on. Geometry is checked against the measurements in the
// README.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { Adb, Image, sleep, waitFor } from "./device.ts";

const PLAYER = "com.wonderhoylabs.otori.testplayer";
const PLAYER_ACTIVITY = `${PLAYER}/.PlayerActivity`;
const TITLES = ["Island Reference Track", "Second Track On The Queue"];
// From the cutout spec: 98 px on the 1280 px panel, scaled to 1080.
const HOLE_DIAMETER_PX = 82.69;
const IDLE_PT = (HOLE_DIAMETER_PX * 0.86) / (1080 / 393.0);
// The camera hole, plus the artwork on one side and the waveform on the other, with a 3 pt gap to each.
const MIN_COMPACT_PT = 2 * (HOLE_DIAMETER_PX / 2 / (1080 / 393.0) + 3 + 8.8 + 22.6);
// The island keeps 21.7 pt, or 60 px, from the status bar items' bounds.
const ICON_GAP_PX = 55;

interface MediaState {
	state: string | null;
	position: number | null;
	title: string | null;
	action: string | null;
}

interface Box {
	left: number;
	right: number;
	top: number;
	bottom: number;
	w: number;
	h: number;
	w_pt: number;
	h_pt: number;
}

class Device {
	readonly adb: Adb;
	readonly out: string;
	readonly width = 1080;
	readonly height = 2410;
	readonly pt = 1080 / 393.0;
	readonly holeCy = 86.06;
	readonly pillTop: number;
	readonly expandedLeft: number;

	constructor(serial: string, out: string) {
		this.adb = new Adb(serial);
		this.out = out;
		this.pillTop = this.holeCy - (37.16 * this.pt) / 2;
		this.expandedLeft = this.width / 2 - (371 * this.pt) / 2;
	}

	shell(command: string): string {
		return this.adb.shell(command);
	}

	screenshot(name?: string): Image {
		const png = this.adb.run("exec-out", "screencap", "-p");
		if (name) writeFileSync(join(this.out, `${name}.png`), png);
		return new Image(png);
	}

	tap(x: number, y: number): void {
		this.shell(`input tap ${x.toFixed(0)} ${y.toFixed(0)}`);
	}

	longPress(x: number, y: number, ms = 700): void {
		this.shell(`input swipe ${x.toFixed(0)} ${y.toFixed(0)} ${x.toFixed(0)} ${y.toFixed(0)} ${ms}`);
	}

	swipe(x0: number, y0: number, x1: number, y1: number, ms = 180): void {
		this.shell(`input swipe ${x0.toFixed(0)} ${y0.toFixed(0)} ${x1.toFixed(0)} ${y1.toFixed(0)} ${ms}`);
	}

	/** The test player's session from dumpsys: state name, position, title and custom action name. */
	mediaState(): MediaState | null {
		const dump = this.shell("dumpsys media_session");
		const i = dump.indexOf(`package=${PLAYER}`);
		if (i < 0) return null;
		const block = dump.slice(i, i + 1600);
		const state = /state=PlaybackState \{state=(\w+)/.exec(block);
		const position = /position=(\d+)/.exec(block);
		const description = /description=([^,\n]*)/.exec(block);
		const action = /mName='?([^,']*)/.exec(block);
		return {
			state: state?.[1] ?? null,
			position: position?.[1] ? Number(position[1]) : null,
			title: description?.[1]?.trim() ?? null,
			action: action?.[1] ?? null,
		};
	}

	focus(): string {
		const pattern = /mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^}]*)\}/;
		const displays = pattern.exec(this.shell("dumpsys window displays"));
		if (displays?.[1]) return displays[1];
		return pattern.exec(this.shell("dumpsys window"))?.[1] ?? "";
	}
}

/** Walks along a line until `run` bright pixels in a row, returning where the island ends. */
function runEnd(line: number[], start: number, step: number, bright = 110, run = 26): number {
	let i = start;
	let count = 0;
	while (i >= 0 && i < line.length) {
		count = (line[i] ?? 0) > bright ? count + 1 : 0;
		if (count >= run) return i - step * run;
		i += step;
	}
	return i;
}

/** Bounding box of the island in pixels, from walking outward through the black region. */
function measure(image: Image, dev: Device): Box {
	const cx = Math.trunc(dev.width / 2);
	const column = image.greyColumn(cx);
	// The hole line is black in every state, so start there.
	const seed = Math.trunc(dev.holeCy);
	let top = seed;
	while (top > 0 && (column[top - 1] ?? 255) < 90) top--;
	const bottom = runEnd(column, seed, 1);
	const h = bottom - top + 1;
	let left: number;
	let right: number;
	if (h > 80 * dev.pt) {
		// Expanded: this row runs between the artwork and the time labels, which is pure black.
		const row = image.greyRow(top + Math.trunc(h * 0.5));
		left = runEnd(row, cx, -1, 110, 8);
		right = runEnd(row, cx, 1, 110, 8);
	} else {
		// A pill. Artwork reaches the mid row, so measure near the top and correct for the capsule's curve.
		const d = 10;
		const row = image.greyRow(top + d);
		const r = h / 2;
		const inset = r - Math.sqrt(Math.max(r * r - (r - d) * (r - d), 0));
		left = runEnd(row, cx, -1, 150, 8) - inset;
		right = runEnd(row, cx, 1, 150, 8) + inset;
	}
	return {
		left,
		right,
		top,
		bottom,
		w: right - left + 1,
		h,
		w_pt: (right - left + 1) / dev.pt,
		h_pt: h / dev.pt,
	};
}

/** Pixels from each end of the island to the nearest dark status bar pixel, or null if there isn't one. */
function gapsToIcons(image: Image, box: Box, dev: Device): [number | null, number | null] {
	const left = Math.trunc(box.left);
	const right = Math.trunc(box.right);
	const darkIn = (x: number) => {
		for (let y = 42; y < 96; y++) if (image.grey(x, y) < 120) return true;
		return false;
	};
	let gapLeft: number | null = null;
	let gapRight: number | null = null;
	for (let d = 2; d < 240; d++) {
		if (gapLeft === null && left - d >= 0 && darkIn(left - d)) gapLeft = d;
		if (gapRight === null && right + d < dev.width && darkIn(right + d)) gapRight = d;
	}
	return [gapLeft, gapRight];
}

class Report {
	passed = 0;
	readonly failed: string[] = [];

	check(name: string, ok: unknown, detail = ""): void {
		console.info(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  [${detail}]` : ""}`);
		if (ok) this.passed++;
		else this.failed.push(name);
	}

	near(name: string, value: number, expected: number, tolerance: number, unit = "pt"): void {
		this.check(
			name,
			Math.abs(value - expected) <= tolerance,
			`${value.toFixed(2)} ${unit}, expected ${expected.toFixed(2)} +/- ${Number.isInteger(tolerance) ? tolerance.toFixed(1) : tolerance}`,
		);
	}
}

function checkCompactLimits(r: Report, name: string, box: Box, dev: Device): void {
	r.check(
		`${name}: width is within its limits`,
		MIN_COMPACT_PT - 1.5 <= box.w_pt && box.w_pt <= 187.2 + 1.0,
		`${box.w_pt.toFixed(1)} pt, limits ${MIN_COMPACT_PT.toFixed(1)} to 187.1`,
	);
	r.near(`${name}: centred on the camera`, (box.left + box.right + 1) / 2, dev.width / 2, 1.5, "px");
}

/** The island keeps its distance from the status bar's icons, unless it has already shrunk as far as it can. */
function checkCompact(r: Report, name: string, image: Image, box: Box, dev: Device): void {
	checkCompactLimits(r, name, box, dev);
	const [left, right] = gapsToIcons(image, box, dev);
	const atMinimum = box.w_pt <= MIN_COMPACT_PT + 1.5;
	const clear = (left === null || left >= ICON_GAP_PX) && (right === null || right >= ICON_GAP_PX);
	r.check(
		`${name}: keeps its distance from the status bar icons`,
		atMinimum || clear,
		`gap left ${left} px, right ${right} px, ${box.w_pt.toFixed(1)} pt wide`,
	);
}

/** Waits until the island has stopped moving, so that slow devices don't fail on timing. */
function settle(dev: Device): void {
	sleep(0.5);
	let last: string | null = null;
	const end = Date.now() + 6000;
	while (Date.now() < end) {
		const box = measure(dev.screenshot(), dev);
		const now = `${Math.round(box.w_pt)}x${Math.round(box.h_pt)}`;
		if (now === last) return;
		last = now;
		sleep(0.35);
	}
}

/** The largest channel spread among some pixels, like numpy's (row.max(axis=1) - row.min(axis=1)).max(). */
function largestSpread(image: Image, y: number, x0: number, x1: number): number {
	let largest = 0;
	for (let x = x0; x < x1; x++) {
		const channels = image.rgb(x, y);
		largest = Math.max(largest, Math.max(...channels) - Math.min(...channels));
	}
	return largest;
}

/** How much a region changed between two screenshots, summed over every channel. */
function difference(a: Image, b: Image, y0: number, y1: number, x0: number, x1: number): number {
	let total = 0;
	for (let y = y0; y < y1; y++) {
		for (let x = x0; x < x1; x++) {
			const [p, q] = [a.rgb(x, y), b.rgb(x, y)];
			total += Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]);
		}
	}
	return total;
}

function main(): void {
	const { values } = parseArgs({
		options: { serial: { type: "string" }, out: { type: "string", default: "build/e2e" } },
	});
	if (!values.serial) throw new Error("usage: pnpm e2e --serial emulator-5554 [--out DIR]");
	const out = values.out ?? "build/e2e";
	mkdirSync(out, { recursive: true });
	const dev = new Device(values.serial, out);
	const r = new Report();
	dev.shell("input keyevent KEYCODE_WAKEUP");

	const pillCx = dev.width / 2;
	const pillCy = dev.holeCy;
	const m = (x: number) => dev.expandedLeft + x * dev.pt;
	const controlY = dev.pillTop + 157.2 * dev.pt;
	const state = () => dev.mediaState()?.state;

	console.info("== setup: no session");
	dev.shell(`am force-stop ${PLAYER}`);
	dev.shell("am start -a android.settings.SETTINGS");
	sleep(2.5);

	console.info("== idle");
	let image = dev.screenshot("01_idle");
	let box = measure(image, dev);
	r.near("idle width", box.w_pt, IDLE_PT, 1.0);
	r.near("idle height", box.h_pt, IDLE_PT, 0.8);
	r.near(
		"idle circle is centred on the camera hole (vertically)",
		(box.top + box.bottom + 1) / 2,
		dev.holeCy,
		1.5,
		"px",
	);
	r.near(
		"idle circle is centred on the camera hole (horizontally)",
		(box.left + box.right + 1) / 2,
		dev.width / 2,
		1.5,
		"px",
	);
	r.check(
		"idle circle stays inside the hole, so no ring can show",
		box.w < HOLE_DIAMETER_PX && box.h < HOLE_DIAMETER_PX,
		`${box.w.toFixed(1)} x ${box.h} px in a ${HOLE_DIAMETER_PX} px hole`,
	);
	r.check("idle circle is pure black", image.sum(pillCx, pillCy) === 0);
	r.check("nothing is drawn around the hole", image.sum(pillCx + 60, pillCy) > 300);

	console.info("== touches outside the island reach the app below");
	const before = dev.focus();
	dev.tap(540, 900);
	const after =
		waitFor(() => {
			const focus = dev.focus();
			return focus && focus !== before ? focus : null;
		}) ?? "";
	dev.shell("input keyevent KEYCODE_BACK");
	waitFor(() => dev.focus() === before);
	r.check("a tap below the island opens the row under it", Boolean(after), `focus ${before} -> ${after}`);

	console.info("== starting playback in the foreground keeps the island idle");
	dev.shell(`am start -n ${PLAYER_ACTIVITY}`);
	waitFor(() => state() === "PLAYING");
	settle(dev);
	box = measure(dev.screenshot("02_foreground_app"), dev);
	r.near("idle while the music app is in front", box.w_pt, IDLE_PT, 1.5);
	const playing = dev.mediaState();
	r.check("session is playing", playing?.state === "PLAYING", JSON.stringify(playing));

	console.info("== compact");
	dev.shell("am start -a android.settings.SETTINGS");
	sleep(2.2);
	image = dev.screenshot("03_compact");
	box = measure(image, dev);
	checkCompact(r, "compact", image, box, dev);
	r.near("compact height", box.h_pt, 37.16, 0.8);
	const roomyWidth = box.w_pt;
	const artX = Math.trunc(box.left + (8.8 + 11.3) * dev.pt);
	r.check("artwork is drawn", image.sum(artX, pillCy) > 60);
	const tintFrom = Math.trunc(box.right - 25 * dev.pt);
	const tintTo = Math.trunc(box.right - 8 * dev.pt);
	r.check("waveform is drawn in a colour", largestSpread(image, Math.trunc(pillCy), tintFrom, tintTo) > 30);

	// Android decides how many icons to show, and sometimes merges these into one, so the island may not need to move.
	console.info("== more notifications: the island makes room for whatever icons Android shows");
	/** Waits for the island to react. It re-measures on each notification, and every 4 s regardless. */
	const settledWidth = (previous: number) => {
		const end = Date.now() + 6500;
		while (Date.now() < end) {
			const width = measure(dev.screenshot(), dev).w_pt;
			if (Math.abs(width - previous) > 1.0) {
				sleep(1.0);
				return;
			}
			sleep(0.4);
		}
	};
	dev.shell(`am broadcast -n ${PLAYER}/.NotifyReceiver --ei count 3`);
	settledWidth(roomyWidth);
	image = dev.screenshot("03b_three_icons");
	box = measure(image, dev);
	checkCompact(r, "with 3 notifications", image, box, dev);
	const fewWidth = box.w_pt;
	dev.shell(`am broadcast -n ${PLAYER}/.NotifyReceiver --ei count 14`);
	settledWidth(fewWidth);
	image = dev.screenshot("03c_many_icons");
	box = measure(image, dev);
	checkCompact(r, "with 14 notifications", image, box, dev);
	r.check("artwork still shows at the minimum", image.sum(Math.trunc(box.left + 20 * dev.pt), pillCy) > 60);
	const crowdedWidth = box.w_pt;
	dev.shell(`am broadcast -n ${PLAYER}/.NotifyReceiver --ei count 0`);
	settledWidth(crowdedWidth);
	box = measure(dev.screenshot(), dev);
	r.near("island grows back when the icons go", box.w_pt, roomyWidth, 1.5);

	console.info("== waveform animates");
	const waveFrom = Math.trunc(box.right - 26 * dev.pt);
	const waveTo = Math.trunc(box.right);
	const frame1 = dev.screenshot();
	sleep(0.35);
	const frame2 = dev.screenshot();
	r.check("waveform frames differ", difference(frame1, frame2, 60, 115, waveFrom, waveTo) > 1500);

	console.info("== long press expands");
	dev.longPress(pillCx, pillCy);
	settle(dev);
	image = dev.screenshot("04_expanded");
	box = measure(image, dev);
	r.near("expanded width", box.w_pt, 371, 2.0);
	r.near("expanded height", box.h_pt, 200.6, 2.0);
	const near = image.grey(540, box.bottom + 8);
	const far = image.grey(540, box.bottom + 120);
	r.check("drop shadow below the island", near < far - 8, `${near} vs ${far}`);

	console.info("== transport controls drive the real session");
	dev.tap(m(185.3), controlY);
	r.check(
		"pause reaches the session",
		waitFor(() => state() === "PAUSED"),
	);
	sleep(0.5);
	dev.screenshot("05_paused");
	dev.tap(m(185.3), controlY);
	r.check(
		"play reaches the session",
		waitFor(() => state() === "PLAYING"),
	);

	const title = () => dev.mediaState()?.title ?? "";
	dev.tap(m(255.5), controlY);
	r.check(
		"next changes the track",
		waitFor(() => title().includes(TITLES[1] ?? "")),
		JSON.stringify(dev.mediaState()),
	);
	sleep(0.5);
	dev.screenshot("06_next_track");
	dev.tap(m(114.6), controlY);
	r.check(
		"previous changes the track back",
		waitFor(() => title().includes(TITLES[0] ?? "")),
	);

	console.info("== scrubbing seeks");
	const barY = dev.pillTop + 113.9 * dev.pt;
	dev.swipe(m(70), barY, m(181.75), barY, 400);
	sleep(0.5);
	const seeked = dev.mediaState();
	const position = seeked?.position ?? -1;
	r.check(
		"dragging the bar to the middle seeks there",
		75_000 <= position && position <= 105_000,
		JSON.stringify(seeked),
	);

	console.info("== collapse");
	dev.tap(540, 1500);
	settle(dev);
	box = measure(dev.screenshot("08_collapsed"), dev);
	r.near("a tap outside collapses to compact", box.w_pt, roomyWidth, 1.5);

	console.info("== swipe down on compact expands, swipe up collapses");
	dev.swipe(pillCx, pillCy, pillCx, pillCy + 110, 150);
	settle(dev);
	box = measure(dev.screenshot(), dev);
	r.near("swipe down expands", box.w_pt, 371, 2.0);
	dev.swipe(pillCx, dev.pillTop + 90 * dev.pt, pillCx, dev.pillTop + 10 * dev.pt, 150);
	settle(dev);
	box = measure(dev.screenshot(), dev);
	r.near("swipe up collapses", box.w_pt, roomyWidth, 1.5);

	console.info("== swipe aside tucks the activity away, and brings it back");
	dev.swipe(pillCx, pillCy, pillCx + 230, pillCy, 150);
	settle(dev);
	box = measure(dev.screenshot("085_swiped_aside"), dev);
	r.near("swipe aside on compact returns to idle", box.w_pt, IDLE_PT, 1.5);
	dev.swipe(pillCx, pillCy, pillCx + 230, pillCy, 150);
	settle(dev);
	box = measure(dev.screenshot(), dev);
	r.near("swipe aside on idle brings it back", box.w_pt, roomyWidth, 1.5);

	console.info("== tap on compact opens the music app");
	dev.tap(pillCx, pillCy);
	const opened = waitFor(() => dev.focus().includes(PLAYER));
	r.check("tap opens the app", opened, dev.focus());
	settle(dev);
	box = measure(dev.screenshot("09_opened_app"), dev);
	r.near("island goes idle when the app comes to the front", box.w_pt, IDLE_PT, 1.5);

	console.info("== paused media leaves after the linger, ended sessions leave at once");
	dev.shell("am start -a android.settings.SETTINGS");
	sleep(2);
	box = measure(dev.screenshot(), dev);
	r.near("compact again after leaving the app", box.w_pt, roomyWidth, 1.5);
	dev.shell(`am force-stop ${PLAYER}`);
	sleep(2.2);
	box = measure(dev.screenshot("10_session_ended"), dev);
	r.near("session ending returns to idle", box.w_pt, IDLE_PT, 1.5);

	console.info("== another app's Live Update shows, follows its progress, leaves when it ends, and opens on tap");
	const live = (progress: number) =>
		dev.shell(`am broadcast -n ${PLAYER}/.LiveUpdateReceiver --ei progress ${progress}`);
	live(40);
	sleep(2.2);
	box = measure(dev.screenshot("12_live_update"), dev);
	r.check("a Live Update opens the island", box.w_pt > IDLE_PT + 60, `${box.w_pt.toFixed(1)} pt wide`);
	live(85);
	sleep(1.5);
	box = measure(dev.screenshot("13_live_update_later"), dev);
	r.check("it stays open as it updates", box.w_pt > IDLE_PT + 60, `${box.w_pt.toFixed(1)} pt wide`);
	live(-1);
	sleep(2.2);
	box = measure(dev.screenshot("14_live_update_ended"), dev);
	r.near("it leaves when the notification goes", box.w_pt, IDLE_PT, 1.5);
	live(40);
	sleep(2.2);
	dev.tap(pillCx, pillCy);
	r.check(
		"tapping it opens its content intent",
		waitFor(() => dev.focus().includes(PLAYER)),
		dev.focus(),
	);
	// The player starts music when it opens, which would hold the island, so stop it. That also clears the update.
	dev.shell(`am force-stop ${PLAYER}`);
	dev.shell("am start -a android.settings.SETTINGS");
	sleep(2.2);
	box = measure(dev.screenshot("15_after_live_update"), dev);
	r.near("idle again once the app that posted it stops", box.w_pt, IDLE_PT, 1.5);

	console.info("== at rest the island takes no touches, so the status bar works right over the camera");
	const window = dev.shell("dumpsys window windows | grep -A12 'Window #.*OtoriTouch' | grep -m1 -E 'fl=|flags='");
	r.check("touch window is not touchable when idle", window.includes("NOT_TOUCHABLE"), window.trim().slice(0, 90));
	const beforeShade = dev.shell("dumpsys window | grep -m1 mCurrentFocus");
	r.check(
		"the shade is closed before the swipe",
		!beforeShade.includes("NotificationShade"),
		beforeShade.trim().slice(0, 70),
	);
	dev.swipe(pillCx, pillCy, pillCx, 1400, 600);
	sleep(1.3);
	dev.screenshot("11_shade_from_camera");
	const shade = dev.shell("dumpsys window | grep -m1 mCurrentFocus");
	r.check(
		"swiping down from the camera opens the shade",
		shade.includes("NotificationShade"),
		shade.trim().slice(0, 70),
	);
	dev.shell("cmd statusbar collapse");
	sleep(0.8);

	const crashes = dev.adb.run("logcat", "-d", "-b", "crash").toString("utf8");
	r.check("no crashes from the app", !crashes.includes("com.wonderhoylabs.otori"));

	console.info(`\n${r.passed} passed, ${r.failed.length} failed`);
	for (const name of r.failed) console.info("  failed:", name);
	process.exit(r.failed.length > 0 ? 1 : 0);
}

main();
