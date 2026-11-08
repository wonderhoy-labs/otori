// Gets a Pixel 10 Pro emulator ready for `pnpm e2e`: matches the phone's display mode, installs the app and the test
// player, and turns on Otori's two permissions. Emulators only, because this changes security settings.
//
//   pnpm prepare-emulator emulator-5554

import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const serial = process.argv[2];
if (!serial) throw new Error("usage: pnpm prepare-emulator emulator-5554");
if (!serial.startsWith("emulator-")) {
	console.error(`Refusing to change security settings on ${serial}. On a real phone, use the switches in the app.`);
	process.exit(1);
}
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const app = "com.wonderhoylabs.otori";
const adb = (...args: string[]) => execFileSync("adb", ["-s", serial, ...args], { stdio: "inherit" });

adb("shell", "wm", "size", "1080x2410");
adb("shell", "wm", "density", "420");
adb("install", "-r", join(root, "android/app/build/outputs/apk/release/app-release.apk"));
adb("install", "-r", "-g", join(root, "android/test-player/build/outputs/apk/debug/test-player-debug.apk"));
adb("shell", "settings", "put", "secure", "enabled_accessibility_services", `${app}/${app}.IslandService`);
adb("shell", "settings", "put", "secure", "accessibility_enabled", "1");
adb("shell", "cmd", "notification", "allow_listener", `${app}/${app}.IslandNotificationListener`);
console.info(`Ready. Run: pnpm e2e --serial ${serial}`);
