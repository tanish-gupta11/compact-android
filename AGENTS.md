# Instructions for AI coding agents (Codex, Claude Code, etc.)

You are building **Compact**, an offline Android app that frees storage by re-compressing camera photos and videos
with no visible quality loss. The full requirements are in `BUILD_SPEC.md` (authoritative) and the background in
`PLAN.md`. Read both completely before writing code.

## Ground rules
1. **Follow `BUILD_SPEC.md`.** If something in it is impossible or clearly wrong, stop, explain why, propose the
   smallest change, and record the decision in `DECISIONS.md` before continuing. Do not silently drop features.
2. **The owner's photos and videos are irreplaceable.** Never select, modify, move or delete any media on a device
   except files you created whose names start with `CompactTest_`. Never weaken the safety rules in BUILD_SPEC §1.3.
3. **Never claim something works unless you ran it.** Every feature needs evidence (test output, numbers, a
   screenshot of the QA build). Put results in `TEST_REPORT.md`, including failures.
4. Keep the app offline: no INTERNET permission, no analytics, no third-party code except the vendored
   `androidx.heifwriter` named in the spec.
5. Reuse proven patterns from the sibling project `..\vault` (build script, QA variant, `Ui` helpers, insets,
   signing). Do not modify `..\vault` or `..\writes`.
6. Work in small verified steps and commit after each milestone (`git init` this folder if needed; never commit
   `signing\`, `build\` or test media; add them to `.gitignore`).
7. Ask the owner before: anything needing their physical action (unlocking the tablet, tapping a camera shutter),
   downloads larger than 200 MB, or changing device settings. Revert device settings you change.
8. Write code that reads like `..\vault`: small focused classes, plain Java 8, framework APIs, clear user-facing
   messages, no dead code.

## Environment
- Windows 11. Shells: PowerShell and Git Bash. In Git Bash run `export MSYS_NO_PATHCONV=1` before `adb shell` with
  `/sdcard/...` paths.
- Android SDK: `..\.android-tools\sdk` (platform android-35, build-tools 35.0.0, platform-tools). JDK 21 on PATH.
- Test device over USB: Samsung SM-X115, Android 16 (`adb devices` should list it). The owner's phone is a POCO F5;
  it is NOT connected. Deliver a checklist for it instead.

## Milestones (do them in order, verify each before moving on)
1. Scaffold: build script + QA variant + empty app that installs and launches; `tools\test.ps1` with a first test.
2. Pure-Java core with unit tests: `Ssim`, `Mp4TimePatcher`, `LosslessJpeg`, bitrate calculator, `JobQueue` states.
3. Video engine end to end on the tablet with `CompactTest_` videos (size, SSIM, metadata, audio checks).
4. Photo engines (HEIC Smart, JPEG Smart, Lossless) on the tablet with `CompactTest_` photos.
5. Scanner, Replacer (MediaStore insert → trash → rename → restore), foreground service, gates, crash recovery.
6. All screens, Compare view, Recently replaced, settings, polish.
7. Full test pass per BUILD_SPEC §7, `TEST_REPORT.md`, README with the POCO F5 checklist, signed
   `releases\compact.apk`, cleanup of test media and the QA app.

## Definition of done
BUILD_SPEC §7.4. When finished, report: what was built, test results with numbers, anything not done or not
verified and why, and exact steps for the owner.
