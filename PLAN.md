# Compact — photo & video space saver for Android (plan)

Status: **plan only, nothing built yet** · Target phone: **POCO F5** (Snapdragon 7+ Gen 2, HyperOS/Android 13–15) · Date: 2026-10-03

---

## 1. Objective

Free up as much phone storage as possible from photos and videos recorded by the camera, **without any quality
loss you can see**, and with **zero risk of losing a memory**:

1. Scan the camera folders and show exactly how much space can be saved before anything changes.
2. Shrink videos a lot (target 50–70%) with no visible difference.
3. Shrink photos with **no quality change at all** by default (smaller savings), with an opt-in
   "visually identical" mode for bigger savings.
4. Keep everything the Gallery needs: date taken, location, orientation, album order, audio.
5. Never delete an original until the smaller copy has been checked, and keep originals recoverable for a while.

Works fully offline. No ads, no internet permission, no accounts.

---

## 2. Is "no quality compromise" possible? (honest answer)

There are two kinds of "no loss", and they are not the same:

| Kind | Meaning | Photos | Videos |
|---|---|---|---|
| **Truly lossless** | Every pixel is mathematically identical to the original | Possible: **5–15% smaller** | **Not possible.** A truly lossless video is *bigger* than the camera file |
| **Visually lossless** | Pixels change very slightly, but nobody can see the difference, even zoomed in on a big screen | **40–60% smaller** | **50–70% smaller** |

**Chosen default (2026-10-03): "Smart" mode** = visually identical, best saving with no visible loss.

| Mode | Visible loss | Photos | Videos |
|---|---|---|---|
| Lossless (option) | none, pixel-identical | 5–10% | not possible |
| **Smart (default)** | **none visible, verified per file** | **~50%** | **~55–65%** |
| Max saving (option) | small, visible in fine detail | ~65–70% | ~70–80% |

- **Smart**: same resolution, fps, colours; audio copied untouched; HEIC for photos, H.265 for videos;
  quality tuned per file and checked (photos SSIM ≥ 0.99, videos ≥ 0.98 on sampled frames); retry at higher
  quality or keep the original if the check fails.
- **Lossless** stays available for people who want pixel-identical photos only.
- **Max saving** is an opt-in for when space matters more than fine detail.

### Why phone videos are so big, and why they can shrink

The phone camera must encode video *in real time* while recording, so it uses a high bitrate and the older
H.264 format to be safe. Re-encoding afterwards with the newer **H.265 (HEVC)** format, which the POCO F5's
chip supports in hardware, stores the same picture in about half the space or less.

### Why "2-hour Full HD movie in 1 GB" is not the target for camera videos

That is roughly 1.1 Mbit/s. Movie releases reach it with slow PC encoders running for hours and clean,
steady film footage. Shaky, noisy phone footage at that size would look visibly blocky. The goal here is
**maximum saving with no visible loss**, not a fixed size.

### Expected savings on POCO F5 (to be confirmed on your actual files in Milestone 1)

| Source (typical) | Now | After | Saving |
|---|---|---|---|
| 1080p 30fps video, H.264 | ~120–150 MB/min | ~40–60 MB/min | ~55–70% |
| 4K 30fps video, H.264 | ~300–400 MB/min | ~110–160 MB/min | ~55–65% |
| Video already recorded in H.265 | — | small or none | usually skipped |
| 12–16 MP JPEG photo, **Lossless mode** | 3–6 MB | 2.7–5.4 MB | ~5–15% |
| 12–16 MP JPEG photo, **Visually identical mode** | 3–6 MB | 1.2–2.5 MB | ~45–60% |
| 50/64 MP JPEG photo, **Visually identical mode** | 12–20 MB | 5–9 MB | ~50–60% |

Example: a phone with 60 GB of camera videos would typically get back **30–40 GB**.

---

## 3. How it will work (technical design)

### 3.1 Videos: hardware H.265 re-encode
- Android `MediaExtractor` → `MediaCodec` decoder → `MediaCodec` **HEVC encoder** (hardware, Snapdragon) →
  `MediaMuxer`. It's fast: roughly real time or faster, so a 10-minute video takes a few minutes.
- **Quality target, not size target.** The bitrate is set per video from its resolution, frame rate and motion,
  aiming for visually identical output. Constant-quality mode is used if the chip supports it.
- **Same resolution and frame rate** as the original. Nothing is downscaled. Slow-motion (120/240 fps) keeps its fps.
- **10-bit/HDR videos** are encoded as HEVC Main10 when the chip supports it, otherwise skipped (never flattened).
- **Audio is copied bit-for-bit** (no re-encoding).
- **Rotation, GPS location and date taken** are carried over.
- A file is **skipped** if it is already H.265/efficient, or if the result would save less than 15%.

### 3.2 Photos: Lossless mode (default, zero quality change)
- JPEG files are rewritten with **optimal Huffman tables + progressive encoding**, the same technique as
  `jpegtran -optimize -progressive` (libjpeg-turbo).
- The decoded image is **bit-for-bit identical**. Only the file packaging gets more efficient.
- All EXIF data (date, camera, GPS, orientation) is kept.
- Implementation: libjpeg-turbo compiled for Android (needs the Android NDK, about 1 GB one-time download), or a
  pure-Java port of the same algorithm if we want to avoid the NDK. To be decided in Milestone 1.
- Proof: after rewriting, both files are fully decoded and compared pixel by pixel. If they differ in a single
  pixel, the original is kept.

### 3.3 Photos: Visually identical mode (optional, off by default)
- Re-encode to **HEIC** (supported by the POCO F5 Gallery) or high-quality JPEG at full resolution.
- Quality chosen per photo so the result passes the quality check in section 6.
- HEIC is ~2× more efficient than JPEG but some old apps/PCs open it less easily. The app will let you pick
  HEIC or JPEG output.

### 3.4 Scanning and selection
- Reads `DCIM/Camera` (and optionally other folders) through Android MediaStore.
- Shows per-file and total "can save X GB", with filters: videos/photos, larger than N MB, older than N days.
- Default suggestion: largest savings first.

---

## 4. Keeping your memories safe

1. **Originals are never touched until the new file is verified** (it opens, has the same duration/resolution,
   passes the quality check, and metadata is copied).
2. The new file is written to the **same folder with the same name and date**, so Gallery order, albums and
   "On this day" style features keep working.
3. The original is then **moved to the system Trash** (Android 11+), recoverable for 30 days, not permanently
   deleted. Android shows its own confirmation. A "Keep originals" option skips this entirely.
4. **Interrupted work is safe.** If the phone dies or the app is closed mid-way, half-finished files are
   discarded on next start and originals are untouched.
5. Runs as a background job with a notification. It **pauses on low battery (<20%) or when the phone gets hot**,
   and resumes later.
6. Cloud copies (Google Photos/Mi Cloud) are not touched.

---

## 5. Screens

1. **Home**: storage used by photos/videos, "You can save about X GB", big **Scan** button.
2. **Review**: list/grid of files with before → after estimate, select all / by type / by size.
3. **Settings before start**:
   - Photos: **Lossless (default)** / Visually identical (HEIC or JPEG)
   - Videos: **Visually identical (default)** / Skip videos
   - After compressing: **Move originals to Trash (default)** / Keep originals
   - Only while charging (optional)
4. **Progress**: current file, time left, GB saved so far, Pause/Stop.
5. **Report**: total saved, files skipped and why, and any failures (originals kept).
6. **Compare** (any file): original vs compressed side by side, zoomable, to judge the quality yourself.

---

## 6. Quality check (the "no visible loss" guarantee)

- **Photos, Lossless mode:** pixel-by-pixel identical, or the file is skipped.
- **Photos, Smart (visually identical):** SSIM ≥ 0.99 (structural similarity; 1.0 = identical, ~0.98+ is treated as
  indistinguishable to the eye) measured at full resolution, or the quality is raised and retried, or skipped.
- **Videos:** sample frames at several points (start, middle, end, high motion) are decoded from both files and
  compared; SSIM must be ≥ 0.98 on every sample, plus same duration (±0.1 s), resolution, fps and audio track.
  Otherwise the bitrate is raised and retried once, then the video is skipped.
- The thresholds will be tuned on real POCO F5 files in Milestone 1 and shown in the report.

---

## 7. Free tip (works today, before the app exists)

POCO F5 Camera → Settings → **Video encoder: H.265** (and **HEIF** for photos if offered). New recordings are
then ~40–50% smaller from the start. The app compresses everything already recorded.

---

## 8. Not included (non-goals)

- Downscaling resolution (e.g. 4K → 1080p). Could be an extra option later, but it is a quality reduction.
- Editing, trimming, filters, cloud upload, sharing.
- Compressing videos from WhatsApp/Telegram (already heavily compressed; little to gain).
- RAW/DNG photos (left untouched).

---

## 9. Risks and how they're handled

| Risk | Handling |
|---|---|
| Phone encoder quirks (colors, rotation, HDR) | Per-video verification; skip on any mismatch; test on real POCO F5 files |
| HyperOS kills long background jobs | Foreground service + resumable queue; guide to allow "No restrictions" battery mode |
| Gallery shows wrong date after replace | Copy DATE_TAKEN/EXIF/MP4 creation time; verify in Gallery during testing |
| Xiaomi Gallery uses its own trash, not the system one | Detect and explain; "Keep originals" mode as fallback |
| Not enough free space to compress a big video | Needs free space ≈ size of one compressed video; checked before each file |
| Lossless JPEG needs native code | NDK build of libjpeg-turbo, or pure-Java port (decided in Milestone 1) |
| I can't test on POCO F5 directly | Develop/test on the Samsung tablet with generated test files; you run the POCO F5 checklist (section 10) |

---

## 10. Test plan

**Automated (PC):** lossless JPEG round trip is pixel-identical on many sample photos; SSIM calculator correct;
queue/resume logic.

**On device (Samsung tablet, generated test media):** 1080p/4K/60fps/slow-mo/portrait/rotated/HDR test videos,
JPEG/HEIC/large photos; verify size, quality score, metadata, Gallery date order, trash/restore,
interrupted job recovery, low-battery pause.

**On your POCO F5 (you run, with a checklist):** scan numbers; compress 3 real videos + 10 real photos in
"Keep originals" mode first; compare side by side; check dates/location in Gallery; then try Trash mode.

---

## 11. Milestones

1. **Measure & decide**: inspect real POCO F5 sample files (codec, bitrate, photo size), confirm HEVC
   encoder capabilities, choose NDK vs pure-Java for lossless JPEG, tune quality thresholds.
2. **Video engine**: H.265 re-encode with audio copy, metadata, verification.
3. **Photo engine**: lossless JPEG optimizer (+ optional HEIC/JPEG visually-identical mode).
4. **App**: scan, review, settings, background job, progress, report, compare screen, trash handling.
5. **Testing & release**: full test plan, signed APK, README with honest limits.

---

## 12. Decisions needed from you

1. **App name** (working name "Compact").
2. ~~Photo mode~~ **Decided: "Smart" (visually identical, verified) is the default for photos and videos.**
3. Originals: **move to Trash (30 days)** or **keep both** by default?
4. One-time **~1 GB Android NDK download** for the lossless JPEG engine OK? (Alternative: pure-Java, slower to build.)
5. For Milestone 1: can you copy **2–3 sample videos and 5 photos** from the POCO F5 to the PC so real numbers
   can be measured? They are only analysed locally and deleted afterwards.
