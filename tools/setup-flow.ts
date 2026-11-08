// Walks the setup flow through the real Settings screens, tapping what a person would tap.
//
//   pnpm setup-flow --serial emulator-5554 [--out DIR]
//
// Emulator only. On a real phone these switches are for its owner to turn on.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { Adb, sleep } from "./device.ts";

interface Node {
	text: string;
	x: number;
	y: number;
	checked: boolean;
	enabled: boolean;
	cls: string;
}

class UI {
	readonly adb: Adb;
	readonly out: string;

	constructor(serial: string, out: string) {
		this.adb = new Adb(serial);
		this.out = out;
	}

	shell(command: string): string {
		return this.adb.shell(command);
	}

	nodes(): Node[] {
		this.shell("uiautomator dump /sdcard/u.xml");
		const xml = this.shell("cat /sdcard/u.xml");
		const nodes: Node[] = [];
		for (const [n] of xml.matchAll(/<node [^>]*?\/?>/g)) {
			const text = / text="([^"]*)"/.exec(n)?.[1] ?? "";
			const description = / content-desc="([^"]*)"/.exec(n)?.[1] ?? "";
			const bounds = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(n);
			if (!bounds) continue;
			const [x0, y0, x1, y1] = bounds.slice(1).map(Number) as [number, number, number, number];
			nodes.push({
				text: text || description,
				x: Math.floor((x0 + x1) / 2),
				y: Math.floor((y0 + y1) / 2),
				checked: / checked="true"/.test(n),
				enabled: / enabled="true"/.test(n),
				cls: / class="([^"]*)"/.exec(n)?.[1] ?? "",
			});
		}
		return nodes;
	}

	tapText(pattern: RegExp, timeout = 8.0): Node | null {
		const end = Date.now() + timeout * 1000;
		while (Date.now() < end) {
			const node = this.nodes().find((n) => n.text && pattern.test(n.text));
			if (node) {
				this.shell(`input tap ${node.x} ${node.y}`);
				sleep(1.2);
				return node;
			}
			sleep(0.5);
		}
		return null;
	}

	toggle(): Node | null {
		return this.nodes().find((n) => n.cls.includes("Switch")) ?? null;
	}

	shot(name: string): void {
		writeFileSync(join(this.out, `${name}.png`), this.adb.run("exec-out", "screencap", "-p"));
	}

	texts(): string[] {
		return this.nodes()
			.map((n) => n.text)
			.filter(Boolean);
	}
}

function turnOn(ui: UI, label: string, shot: string, confirmShot?: string): void {
	console.info("tap button:", Boolean(ui.tapText(/^Turn On$/i)));
	sleep(1.0);
	if (ui.toggle()) console.info("went straight to Otori's own page");
	else {
		console.info("list page, so tap Otori:", Boolean(ui.tapText(/^Otori$/i)));
		sleep(0.8);
	}
	const screen = ui.texts();
	console.info("screen:", screen.slice(0, 10));
	console.info(
		"restricted setting block:",
		screen.some((t) => /restricted/i.test(t)),
	);
	ui.shot(shot);
	const toggle = ui.toggle();
	console.info("toggle:", toggle && { checked: toggle.checked, enabled: toggle.enabled });
	if (toggle) {
		ui.shell(`input tap ${toggle.x} ${toggle.y}`);
		sleep(1.2);
		if (confirmShot) ui.shot(confirmShot);
		console.info("dialog:", ui.texts().slice(0, 8));
		console.info("tap Allow:", Boolean(ui.tapText(/^(Allow|OK|Turn on)$/i, 4)));
	}
	console.info(`(${label} done)`);
}

function main(): void {
	const { values } = parseArgs({
		options: { serial: { type: "string" }, out: { type: "string", default: "build/setup-flow" } },
	});
	if (!values.serial) throw new Error("usage: pnpm setup-flow --serial emulator-5554 [--out DIR]");
	const out = values.out ?? "build/setup-flow";
	mkdirSync(out, { recursive: true });
	const ui = new UI(values.serial, out);
	const openApp = () => ui.shell("am start --activity-clear-top -n com.wonderhoylabs.otori/.MainActivity");
	ui.shell("input keyevent KEYCODE_HOME");
	sleep(0.8);

	console.info("-- open the app");
	openApp();
	sleep(1.5);
	ui.shot("1_app_before");
	console.info("screen:", ui.texts().slice(0, 6));

	console.info("-- accessibility");
	turnOn(ui, "accessibility", "2_accessibility_detail", "3_accessibility_confirm");
	sleep(1.5);
	console.info("enabled services:", ui.shell("settings get secure enabled_accessibility_services").trim());

	console.info("-- notification access");
	openApp();
	sleep(1.2);
	turnOn(ui, "notification access", "4_notification_detail");
	sleep(1.2);
	const listeners = ui.shell("settings get secure enabled_notification_listeners").trim();
	console.info("listener enabled:", listeners.includes("com.wonderhoylabs.otori"));

	openApp();
	sleep(1.5);
	ui.shot("5_app_after");
	console.info("app now says:", ui.texts().slice(0, 4));
}

main();
