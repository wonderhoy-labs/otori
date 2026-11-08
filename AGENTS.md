# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to Otori.

## What Otori is

A faithful Dynamic Island for the Pixel, around the camera hole, with music controls. An accessibility overlay draws the
island, a notification listener follows media sessions, and every size, colour and spring was measured from the real
iOS island. Named for Otori Emu of Wonderlands×Showtime, whose "Wonderhoy!" the org is named after.

## Layout

| Path | What |
| --- | --- |
| `android/app/src/main/kotlin/com/wonderhoylabs/otori/Spec.kt` | Every measured size, colour and timing |
| `android/app/src/main/kotlin/com/wonderhoylabs/otori/IslandView.kt` | Draws and animates the island, and its gestures |
| `android/app/src/main/kotlin/com/wonderhoylabs/otori/IslandService.kt` | The overlay windows, and what to show when |
| `android/app/src/main/kotlin/com/wonderhoylabs/otori/LiveUpdates.kt` | Other apps' Live Updates, Otori's contract with them |
| `android/app/src/main/kotlin/com/wonderhoylabs/otori/MainActivity.kt` | The Compose setup screen |
| `android/test-player/` | A small music app with a real media session, and a Live Update to post, for tests only |
| `tools/e2e.ts`, `tools/setup-flow.ts`, `tools/prepare-emulator.ts` | Device tests (`pnpm e2e`, `pnpm setup-flow`, `pnpm prepare-emulator`) |
| `crates/otori-measure/` | Tracks the island in recordings and fits springs (`pnpm measure`) |
| `tools/measure/ios_full.csv` | The tracked motion of the real iOS island |
| `tools/apple/` | The iOS harness and window recorder that measured it |

## Build, test and format

```bash
pnpm install && pnpm unify
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :test-player:assembleDebug
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
```

The end-to-end test needs the `Pixel_10_Pro_Island` emulator (a Pixel 10 Pro, API 37), and both APKs built:

```bash
pnpm prepare-emulator emulator-5554 && pnpm e2e --serial emulator-5554
```

## Things to know

- **Never run the device tests on a real phone.** They tap at fixed positions whatever is on screen, and one run once
  landed taps in its owner's apps. `prepare-emulator` refuses anything that isn't `emulator-*`, because it changes
  security settings that are the phone owner's to change.
- **Two or more devices are often attached** (the maintainer's Pixel and an emulator). Always pass `--serial`, or set
  `ANDROID_SERIAL`.
- **Match the real thing.** Changes to the island's look or motion are measured against `tools/measure/ios_full.csv`
  with `pnpm measure compare`, not judged by eye.
- `am force-stop` kills the accessibility service, and Android doesn't restart it. Toggle the setting to bring it back.
- **Other apps reach the island through Android's own Live Updates**, read in `LiveUpdates.kt`: promoted ongoing
  notifications, their short critical text and `ProgressStyle`. That's Otori's library, so keep it to Android's API, and
  document anything new it reads in the README's "Use it in your app". Don't invent a private API for it without asking.
- Never replace the Android signing key. CI signs with the `OTORI_KEYSTORE_BASE64`, `OTORI_KEYSTORE_PASSWORD`,
  `OTORI_KEY_ALIAS` and `OTORI_KEY_PASSWORD` secrets.

## Exceptions to the guideline

- **The island is not Material.** It imitates Apple's Dynamic Island exactly, so `IslandView` is a custom View drawn
  with Inter and iOS's colours. That goes for `PreviewActivity` too, which shows the island in an ordinary window on
  iOS's grey, the background its motion was measured against.
- **Swift in `tools/apple/`.** The reference island only runs on iOS, so its harness and the macOS window recorder are
  Swift, with the harness's own `build.sh`.
- The `pnpm measure fit` two-phase collapse fit lands on a slightly different local optimum than the scipy script it
  replaced (14.58 against 14.60 pt RMS). Every other number matches scipy's.
