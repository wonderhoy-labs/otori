// What the device tools share: adb, screenshots, and waiting.

import { spawnSync } from "node:child_process";
import { PNG } from "pngjs";

/** Blocks for a while, like Python's time.sleep, so the tests read top to bottom. */
export function sleep(seconds: number): void {
	Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, seconds * 1000);
}

/** A screenshot, as RGB rows like Pillow's RGB image. */
export class Image {
	readonly width: number;
	readonly height: number;
	private readonly rgba: Buffer;

	constructor(png: Buffer) {
		const decoded = PNG.sync.read(png);
		this.width = decoded.width;
		this.height = decoded.height;
		this.rgba = decoded.data;
	}

	/** The three channels at a pixel. */
	rgb(x: number, y: number): [number, number, number] {
		const i = (Math.trunc(y) * this.width + Math.trunc(x)) * 4;
		return [this.rgba[i] ?? 0, this.rgba[i + 1] ?? 0, this.rgba[i + 2] ?? 0];
	}

	/** Their sum, like numpy's a[y, x].sum(). */
	sum(x: number, y: number): number {
		const [r, g, b] = this.rgb(x, y);
		return r + g + b;
	}

	/** Grey level the way Pillow's convert("L") makes it. */
	grey(x: number, y: number): number {
		const [r, g, b] = this.rgb(x, y);
		return (r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16;
	}

	greyRow(y: number): number[] {
		return Array.from({ length: this.width }, (_, x) => this.grey(x, y));
	}

	greyColumn(x: number): number[] {
		return Array.from({ length: this.height }, (_, y) => this.grey(x, y));
	}
}

export class Adb {
	readonly serial: string;

	constructor(serial: string) {
		this.serial = serial;
	}

	run(...args: string[]): Buffer {
		return (
			spawnSync("adb", ["-s", this.serial, ...args], { maxBuffer: 64 * 1024 * 1024 }).stdout ?? Buffer.alloc(0)
		);
	}

	shell(command: string): string {
		return this.run("shell", command).toString("utf8");
	}

	screenshot(): Image {
		return new Image(this.run("exec-out", "screencap", "-p"));
	}
}

/** Checks something until it's true or the time runs out, then returns what it last saw. */
export function waitFor<T>(predicate: () => T, timeout = 6.0, interval = 0.25): T {
	const end = Date.now() + timeout * 1000;
	while (Date.now() < end) {
		const value = predicate();
		if (value) return value;
		sleep(interval);
	}
	return predicate();
}
