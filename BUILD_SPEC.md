# Compact — Build Specification (v1.0)

This is the authoritative spec for building **Compact**, an offline Android app that frees storage by re-compressing
camera photos and videos with **no visible quality loss**. It is written so another engineer or AI agent can build it
without further context. Read `PLAN.md` for the product rationale; this file wins if they disagree.

- Owner's phone: **POCO F5** (Snapdragon 7+ Gen 2, HyperOS, Android 13–15). Test device available over USB:
  **Samsung SM-X115** (MediaTek Helio G99, Android 16, secure lock screen, the owner unlocks it on request).
- Workspace: `C:\Users\Dads Gift(Razon)\Documents\ChatGPT\apps\compress` (Windows 11, Git Bash + PowerShell).
- Sibling project to copy patterns from: `..\vault` (proven no-Gradle build, QA variant, UI helpers, signing).

---

## 1. Product requirements

### 1.1 Modes
| Mode | Photos | Videos | Default |
|---|---|---|---|
| **Smart** | Re-encode to HEIC (or high-quality JPEG if the user picks JPEG output), full resolution, quality chosen per file so SSIM ≥ 0.99 | H.265 (HEVC) re-encode, same resolution/fps, quality chosen so sampled-frame SSIM ≥ 0.98; audio copied bit-for-bit | **Yes** |
| Lossless | JPEG Huffman re-optimization; decoded pixels must be bit-identical | Not offered (videos skipped) | No |
| Max saving | HEIC/JPEG with SSIM ≥ 0.97 | HEVC with SSIM ≥ 0.95 | No |

Never downscale resolution, never change frame rate, never drop audio, never touch RAW/DNG.

### 1.2 Expected outcomes (acceptance targets on typical camera files)
- 1080p30 H.264 camera video: ≥ 50% smaller in Smart mode.
- 4K30 H.264 camera video: ≥ 50% smaller in Smart mode.
- 12–16 MP camera JPEG: ≥ 40% smaller in Smart/HEIC; ≥ 3% in Lossless (or skipped).
- A file is **skipped** (left untouched, reported with reason) if the saving would be < 15% (Smart/Max) or < 2% (Lossless),
  if verification fails twice, or if it is unsupported (HDR, already HEVC/HEIC at efficient bitrate, corrupt, DRM).

### 1.3 Safety requirements (non-negotiable)
1. An original is never modified, moved or deleted until its replacement is fully written, re-opened and verified.
2. Default after-action: originals moved to the **system Trash** (`MediaStore.createTrashRequest`, recoverable ~30 days).
   Option: **Keep originals** (new files saved alongside with suffix ` (compact)`).
3. Gallery order must not change: replacement keeps the same folder, base name, `DATE_TAKEN`, EXIF/MP4 creation time,
   GPS and orientation.
4. Crash/kill/power loss at any point leaves originals intact; partial outputs are cleaned on next launch.
5. In-app **"Recently replaced"** list can restore originals from Trash (`createTrashRequest(uris, false)`) for 30 days.
6. Cloud copies are not touched (tell the user).

### 1.4 Screens
1. **Home**: photo/video storage used, "You can save about X GB" (after scan), Scan button, last report summary.
2. **Review**: grid/list of candidates with original size → estimated size, type/size/date filters, select all,
   sort by saving. Estimates come from §3.6.
3. **Options sheet** before start: Mode (Smart/Lossless/Max), Photo output (HEIC default / JPEG), After compressing
   (Trash originals default / Keep originals), "Only while charging" toggle.
4. **Progress**: current file + thumbnail, file x of y, GB saved so far, ETA, Pause/Resume/Stop. Continues in
   background with a foreground notification.
5. **Report**: saved total, per-file result (saved / skipped + reason / failed + reason). Failed = original untouched.
6. **Compare**: original vs compressed side by side with synced pinch-zoom (photos) or A/B toggle at the same
   timestamp (videos); shows sizes and SSIM score.
7. **Recently replaced**: restore originals from Trash.
8. **Settings/About**: defaults, thermal/battery behaviour, licences, "How it works" (honest explanation of
   visually-identical vs lossless).

Visual style: dark, clean, same design language as `..\vault` (`Ui.java` colours/buttons). Phone-first layout, works
on tablets and in landscape, edge-to-edge with insets handled.

---

## 2. Platform & build

- `minSdk 30` (Android 11: required for `createTrashRequest`/`createWriteRequest`; POCO F5 ships Android 13+),
  `targetSdk 35`, compile against `android-35`.
- Language: Java 8 source level (like `..\vault`), framework APIs only, plus **one vendored library**:
  `androidx.heifwriter:heifwriter` (Apache-2.0) for HEIC output. Download the AAR from Google Maven, extract
  `classes.jar`, keep its licence in `THIRD_PARTY.md`. No other third-party code. If HeifWriter proves unusable on a
  device, fall back to JPEG output for photos (see §4.3).
- Build like `..\vault\tools\build.ps1`: aapt (with `--custom-package com.compact`) → javac → d8 → zipalign →
  apksigner, own keystore created on first build in `signing\` (DPAPI-protected password). `-Qa` builds
  `com.compact.qa` (debuggable, label "Compact QA") for device testing. `-Install` installs.
- `tools\test.ps1` runs pure-JVM unit tests (no Android needed) for every platform-independent component.
- SDK location: `..\.android-tools\sdk` (platform 35, build-tools 35.0.0, platform-tools/adb). JDK 21 on PATH.
- Git Bash: export `MSYS_NO_PATHCONV=1` before `adb shell` commands with `/sdcard/...` paths.

### 2.1 Manifest essentials
- Permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (33+), `READ_MEDIA_VISUAL_USER_SELECTED` (34+, handle partial
  access by asking for full access with an explanation), `READ_EXTERNAL_STORAGE maxSdkVersion=32`,
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`,
  `WAKE_LOCK`. Optional `MANAGE_MEDIA` (special access, Android 12+) to skip per-batch system dialogs when granted.
- **No INTERNET permission.** `allowBackup="false"`.
- Foreground service type: `mediaProcessing` on API 35+, `dataSync` below. Respect the 6-hour limit:
  checkpoint and stop gracefully on `onTimeout`, resume later.

---

## 3. Architecture

```
ui/        HomeActivity, ReviewActivity, ProgressActivity, ReportActivity, CompareActivity, ReplacedActivity, SettingsActivity
work/      CompressService (foreground), JobQueue (SQLite), Scheduler (battery/thermal/charging gates)
media/     MediaScanner (MediaStore queries), Replacer (insert/rename/trash/restore), MetadataCopier
video/     VideoTranscoder, VideoProbe, Mp4TimePatcher, AudioCopier
photo/     HeicEncoder, JpegEncoder, LosslessJpeg (parser + Huffman optimizer + writer), ExifCopier
quality/   Ssim (pure Java), FrameSampler, QualityGate
util/      Ui, Prefs, Log, Files
```

### 3.1 Job pipeline (per file)
`PENDING → ANALYSED → ENCODING → ENCODED → VERIFIED → PUBLISHED → ORIGINAL_TRASHED → DONE`
(or `SKIPPED(reason)` / `FAILED(reason)` at any step). Persist state in SQLite after every transition. On restart,
any job in ENCODING/ENCODED is reset to ANALYSED and its temp output deleted. Jobs in PUBLISHED without
ORIGINAL_TRASHED are re-queued for the trash step only.

Temp outputs live in `getCacheDir()/work/<jobId>.<ext>`. Before each job check free space ≥ 1.2 × estimated output
+ 200 MB; otherwise pause the queue with a "free up space" message.

### 3.2 Scanning (`MediaScanner`)
Query `MediaStore.Images` and `MediaStore.Video` for `RELATIVE_PATH LIKE 'DCIM/Camera%'` by default (setting to add
other folders). Exclude `IS_TRASHED`, `IS_PENDING`, RAW/DNG, files < 300 KB (photos) or < 5 MB (videos), and files whose
`OWNER_PACKAGE_NAME` is this app (already processed). Keep `_id`, display name, relative path, size, mime,
date_taken, date_modified, width, height, orientation, duration.

### 3.3 Gates (`Scheduler`)
Pause when: battery < 20% and not charging; `PowerManager.getCurrentThermalStatus() >= THERMAL_STATUS_SEVERE`;
"only while charging" enabled and not charging; free space low. Resume automatically when conditions clear.
Hold a partial wake lock only while a job is running.

### 3.4 Publishing & replacing (`Replacer`)
1. Insert the verified output via `MediaStore` into the **same** `RELATIVE_PATH` with display name
   `<base> (compact).<ext>`, `IS_PENDING=1`, copy bytes, set `DATE_TAKEN` = original, `IS_PENDING=0`.
   Set the file's last-modified time to the original's.
2. Batch originals (up to 100) into one `createTrashRequest(uris, true)` → one system dialog.
   If `MANAGE_MEDIA` is granted, trash directly without a dialog.
3. After the user approves, rename each new item's `DISPLAY_NAME` to the original base name (+ new extension if it
   changed, e.g. `.jpg` → `.heic`). If the user denies, leave both copies and mark jobs "kept both".
4. Record (newUri, originalUri, sizes, time) for "Recently replaced". Restoring = `createTrashRequest(orig, false)`
   then delete the compact copy (`createDeleteRequest` for our own file is not needed: we own it).
5. Xiaomi/HyperOS Gallery may show its own trash instead of the system one. Detect `Build.MANUFACTURER` Xiaomi
   and explain in the UI that originals are in the system trash, restorable from Compact for 30 days.

### 3.5 Metadata preservation (`MetadataCopier`, `ExifCopier`, `Mp4TimePatcher`)
- Photos: copy all EXIF (DateTimeOriginal, OffsetTime*, GPS*, Make/Model, exposure data, Orientation) into the
  output. For HEIC use `HeifWriter.addExifData`; for JPEG use `android.media.ExifInterface` `saveAttributes()`.
  Verify by reading the output with `ExifInterface`.
- Videos: rotation via `MediaMuxer.setOrientationHint(rotation)`; GPS via `MediaMuxer.setLocation` (parse
  `METADATA_KEY_LOCATION` ISO-6709 string); **creation time**: MediaMuxer writes its own `mvhd/tkhd/mdhd` times, so
  `Mp4TimePatcher` rewrites `creation_time`/`modification_time` in `moov/mvhd`, every `trak/tkhd` and `trak/mdia/mdhd`
  with the original's `mvhd` values (handle version 0 = 32-bit and version 1 = 64-bit fields). Pure Java, unit tested.
- MediaStore `DATE_TAKEN` set explicitly on insert (§3.4).

### 3.6 Size estimation (for the Review screen)
- Video: estimated output bitrate from §4.1 × duration + audio size. Show "≈".
- Photo Smart: 0.5 × original for HEIC, 0.7 × for JPEG output; Lossless: 0.92 ×. Refine with the running average
  of actual results in this session.

---

## 4. Engines

### 4.1 Video (`VideoTranscoder`)
- Probe with `MediaExtractor` + `MediaMetadataRetriever`: codec, width, height, rotation, fps (from `KEY_FRAME_RATE`
  or computed from the first 60 sample timestamps), bitrate (file size / duration if missing), color transfer,
  profile, audio track format.
- **Skip** when: HDR (`KEY_COLOR_TRANSFER` = ST2084 or HLG, or HEVC Main10/HDR profiles) in v1; source already HEVC
  with bitrate ≤ 1.4 × target; no hardware HEVC encoder (`MediaCodecList` with `isHardwareAccelerated()`); audio
  codec not MP4-muxable; DRM; duration < 2 s.
- Pipeline: decoder renders directly into the encoder's input surface (`encoder.createInputSurface()`,
  `decoder.configure(fmt, inputSurface, null, 0)`). No GL needed because size/rotation are unchanged. Release decoder
  output buffers with `render=true` so timestamps carry over (keeps variable frame rate intact). Signal end of
  stream with `signalEndOfInputStream()`.
- Encoder: `video/hevc`, `HEVCProfileMain`, same width/height, `KEY_FRAME_RATE` = source fps,
  `KEY_I_FRAME_INTERVAL` = 2, `KEY_COLOR_FORMAT` = `COLOR_FormatSurface`. Bitrate mode:
  - If `BITRATE_MODE_CQ` is supported, use it with `KEY_QUALITY` tuned per mode (start high, e.g. 85% of range for Smart).
  - Otherwise `BITRATE_MODE_VBR` with target bitrate = width × height × fps × bpp, where bpp = **0.10 (Smart)**,
    0.13 (Smart retry), 0.065 (Max). Never exceed 0.6 × source bitrate (if it would, skip: no real saving).
    Examples: 1080p30 Smart ≈ 6.2 Mbps; 4K30 Smart ≈ 24.9 Mbps.
- Audio: `AudioCopier` copies every audio sample with `MediaExtractor.readSampleData` → `MediaMuxer.writeSampleData`
  (same format, no re-encode). Interleave by timestamp.
- Muxer start only after the encoder's `INFO_OUTPUT_FORMAT_CHANGED` gives the real format.
- Progress = last video PTS / duration.
- On any codec exception: release everything, delete temp, retry once with VBR at the retry bpp, then `FAILED`.
- **Verify** (`QualityGate`): output opens; duration within ±0.15 s; same width/height/rotation; audio track present
  if the source had one, with identical sample count; size saving ≥ 15%; `FrameSampler` grabs frames at 10%, 30%,
  50%, 70%, 90% of duration from both files (`getFrameAtTime(t, OPTION_CLOSEST)`, scaled to ≤ 1280 px on the long
  side, same scale for both) and **every** frame must reach the mode's SSIM threshold. If the first attempt fails
  SSIM, retry once at the higher bpp/quality; then skip.

### 4.2 Photo Smart, HEIC output (`HeicEncoder`)
- Decode the original with `BitmapFactory` (no auto-rotation; keep EXIF Orientation as metadata) using
  `ARGB_8888`. For images > 50 MP, decode via `BitmapRegionDecoder` in tiles if memory is short; if still not
  possible, skip with reason "too large for this phone's memory".
- `HeifWriter.Builder(path, w, h, INPUT_MODE_BITMAP).setQuality(q)`, add bitmap, add EXIF, stop, close.
- Quality search: start q=90 (Smart), compute SSIM vs original at full resolution (tiled, §5). If < threshold,
  q += 4 up to 98; if ≥ threshold, try one step lower (q −= 4) to save more, keep the smallest passing result.
  Max 3 encodes per photo.
- Skip when the source is already HEIC/HEIF, or the best passing result saves < 15%.

### 4.3 Photo Smart, JPEG output (`JpegEncoder`) (user option or HEIC fallback)
- `Bitmap.compress(JPEG, q)` with the same quality search (start q=92). Copy EXIF with `ExifInterface`.
  Typical saving 25–40% vs camera JPEGs saved at q95+.

### 4.4 Photo Lossless (`LosslessJpeg`), pure Java
- Parse markers; support baseline (SOF0/SOF1) and progressive (SOF2) Huffman JPEGs, 8-bit, 1 or 3 components, any
  sampling factors, restart intervals (DRI/RSTn). Reject arithmetic coding, 12-bit, lossless JPEG (skip).
- Entropy-decode all scans into quantized DCT coefficient blocks (no IDCT needed).
- Re-encode as **baseline, interleaved, with optimal Huffman tables** built from symbol statistics (two-pass,
  standard JPEG Annex K.2 code-length limiting to 16 bits). Keep every APPn/COM marker byte-for-byte and the same
  quantization tables. Preserve restart interval if present.
- Unit tests: generate JPEGs on the JVM with `javax.imageio` at several qualities/subsamplings, run the optimizer,
  decode both with ImageIO and assert identical pixels; byte-identical APPn segments; output smaller or equal.
- On device: decode both with `BitmapFactory` and compare every pixel (tiled) before publishing.
  Any difference means skip.

---

## 5. Quality metric (`Ssim`), pure Java, unit tested
- Convert to luma Y = 0.299R + 0.587G + 0.114B (8-bit), compute mean SSIM over 8×8 windows with stride 4,
  constants C1 = (0.01·255)², C2 = (0.03·255)² (Wang et al. 2004).
- For large photos, process in tiles of 1024×1024 with BitmapRegionDecoder for both images, aggregate the mean
  weighted by window count. Report the **minimum tile score** as well; require min tile ≥ threshold − 0.01 so a
  damaged region cannot hide in a good average.
- Tests: identical images = 1.0; known noise levels give expected monotonic decrease; shifted image scores low.

---

## 6. Non-goals for v1
Downscaling, trimming/editing, cloud upload, WhatsApp/Telegram folders by default, HDR video re-encode, RAW,
background auto-compress of new photos (possible v2: a "compress new camera files nightly while charging" job).

---

## 7. Testing & acceptance

### 7.1 Automated (PC, `tools\test.ps1`)
Ssim, LosslessJpeg round trips, Mp4TimePatcher (synthetic MP4 box trees, v0/v1), bitrate/bpp calculator,
JobQueue state machine (including crash-recovery transitions), ISO-6709 location parser.

### 7.2 Device (QA build on the Samsung SM-X115; owner unlocks it when asked)
Create test media without touching the owner's own files: record with the tablet camera via
`am start -a android.media.action.VIDEO_CAPTURE` / `IMAGE_CAPTURE` (owner may need to tap the shutter), plus
`screenrecord` clips and generated JPEGs pushed to `/sdcard/DCIM/Camera/CompactTest_*`. Cases: 1080p and 4K (if the
tablet supports it), portrait/rotated, 60 fps, slow-motion if available, with and without audio, GPS-tagged
photos, a 50+ MP JPEG (generated on the PC with Python PIL), a corrupt file, an already-HEVC video.

For every case verify and record in `TEST_REPORT.md`: size before/after, SSIM, duration/resolution/fps/rotation,
audio sample count, EXIF and GPS preserved, Gallery shows the same date and position, Trash + restore works, kill
during encoding (`adb shell am force-stop`) leaves the original intact and resumes, low battery/thermal pause (simulate
with `adb shell dumpsys battery set level 15`, then `dumpsys battery reset`).

Never select or modify the owner's personal photos/videos during testing. Only `CompactTest_*` files. Delete all
test media and uninstall the QA app at the end. Restore any device setting changed.

### 7.3 Owner checklist for the POCO F5 (deliver in README)
Install → Scan → pick 3 videos + 10 photos → **Keep originals** mode first → Compare screen → check Gallery dates
and locations → then Trash mode on a few files → Recently replaced → Restore one.

### 7.4 Definition of done
- All automated tests pass; QA device checks pass and are recorded in `TEST_REPORT.md` with numbers.
- Smart-mode acceptance targets in §1.2 met on the device test media (or the report explains exactly why not).
- Signed `releases\compact.apk`, `README.md` (features, honest limits, POCO F5 checklist, how to build),
  `THIRD_PARTY.md`.
- No crash in logcat (`adb logcat -b crash`) during the full test run.
