# Compact test report

## 2026-10-03 — 0.2 validation build

Status: compression and application workflow implemented; **not production-certified**. The owner explicitly chose “Continue without device testing for now.” No physical-device result is represented as a pass.

### Executed host checks

`tools/test.ps1`: **PASS, 66 core assertions plus 60 adversarial JPEG cases**. Full output is in `reports/host-tests.txt`.

- SSIM identity: 1.0.
- SSIM small synthetic noise: 0.9994626051171537.
- SSIM larger synthetic noise: 0.925258964095235.
- Spatial shift and luminance-change detection: pass.
- JPEG: 12 initial colour/grayscale cases at qualities 0.5, 0.9, 1.0, baseline/progressive. Every decoded pixel and injected APP1 bytes preserved.
- 60 additional JPEG cases: baseline/progressive; 4:4:4/4:2:0; restart intervals 0/3/17; flat, edge and noisy images; odd dimensions. Both the selected output AND the candidate before size fallback were decoded and pixel-compared.
- MP4 creation/modification times: v0/v1, standard/extended-size nested boxes; entire synthetic trees compared after patching, including every track/media-header timestamp.
- Corrupt JPEG/MP4 rejection, bitrate calculation, ISO-6709 parsing/range rejection, state recovery and premature-trash guards: pass.

Selected real host-test measurements (generated fixtures, not phone-camera acceptance data):

| JPEG case | Before bytes | After bytes | Pixel result |
|---|---:|---:|---|
| Baseline RGB q0.5 | 7,979 | 7,199 | Identical |
| Baseline RGB q0.9 | 19,171 | 17,625 | Identical |
| Baseline RGB q1.0 | 46,701 | 40,192 | Identical |
| Progressive RGB q0.5 | 7,267 | 7,199 | Identical |
| Efficient progressive RGB q0.9 | 16,827 | 16,827 | Original retained |

### APK checks

- Release and QA Java compilation, DEX generation, resource packaging, alignment and signing: pass.
- `tools/verify-apk.ps1` and `-Qa`: signature, package, launcher, minSdk 30, targetSdk 35, no Internet permission, essential packaged classes, bundled license, non-debuggable release and absence of QA instrumentation in release.
- Launcher corrected to `com.compact.MainActivity` in both package variants.
- The AAR has class-retention AndroidX annotation references not present on the compile classpath; javac emits one warning. Executable dependency inspection found framework/JDK and internal HeifWriter references only.

### Implemented but NOT executed on Android

HEVC/CQ/VBR hardware encoding; audio bitstream/timestamp comparison; video frame sampling; HEIC/JPEG Android encoding; native tile decode and EXIF checks; scanning/permissions; actual SQLite transactions and recovery; MediaStore publish/hash/date/Trash/restore; UI layout/zoom/video compare; background notification; battery/thermal/storage gates; Android six-hour timeout; process-death/power-loss paths.

`QaChecks` and `tools/device-check.ps1` are compiled tools for generated private photo/SQLite checks, but were not run. No device screenshot, audio sample-count measurement, video SSIM value or Galaxy/POCO savings number exists yet.

### Remaining acceptance

Run BUILD_SPEC §7's device matrix with generated CompactTest_* media. Specifically: 1080p/4K (supported resolutions), rotated/60fps, with/without audio, HDR rejection, large/GPS-tagged photos, denial of permissions/Trash, restoration, kill during every publication state, low battery/heat, and Gallery order. Inspect crash log for the test interval. The 50% video and 40% HEIC savings targets remain unmeasured.

No device was installed to, no personal media was accessed, and no device settings were changed in this session; there is no device cleanup to perform.

### Failures found and repaired

- Earlier scaffold's PowerShell quoting error and incorrect QA activity package reference.
- Initial native QA source used an AndroidX-only EXIF setter with framework ExifInterface; replaced with framework GPS attributes. Rebuilt successfully.
- Static review found nullable DRM metadata, extended-size MP4 headers, interrupted publishing retry loops, restored-name conflicts, pending-dialog recovery, and destroyed-activity callbacks; fixes are included. Their Android runtime paths still require the device tests above.

## 2026-10-03 — 1.0 device validation (Samsung SM-X115, Android 16, MediaTek)

Independent review of the 0.2 build, then fixes, then a full device run with generated `CompactTest_*` media
(H.264 1080p30/60, 4K30, portrait/rotated, no-audio, already-HEVC; 12 MP JPEGs with EXIF date/GPS/orientation 1/3/6/8,
4:4:4 and 4:2:0; one 50 MP JPEG). Media generated with FFmpeg/Pillow from Windows stock wallpapers plus sensor-like noise.

### Defects found in 0.2 on the device (all fixed)
1. All 5 photos failed ("Photo failed quality verification"): the MediaTek HEIC encoder ignores `setQuality`
   (q80–q98 identical ~150 KB, SSIM 0.88) and the engine never left HEIC. Fixed: detect quality-insensitive HEIC,
   fall back to JPEG search, then to Lossless.
2. No Lossless fallback in Smart; files that could not save 15% lossy saved nothing. Fixed.
3. 4K videos always skipped: the "already efficient" rule rejected typical 40 Mbps phone 4K. Fixed: skip only when a
   re-encode cannot save 15%.
4. 1080p60/4K on an encoder without support reported "No compatible hardware HEVC encoder". Fixed: size/rate check
   with a clear message ("can't handle 1920x1080 at 60 fps"); this tablet's encoder tops out at 2560 px / 30 fps.
5. Portrait video false failure (SSIM 0.947): frame sampling compared frames one apart. FFmpeg over all frames gave
   SSIM 0.977 / PSNR 43.8 dB. Fixed: best-aligned neighbour (±1 frame).
6. Video quality was checked at 1280 px, hiding detail loss (CQ: 0.982 at 1280 vs 0.966 at 1920). Fixed: up to 1920 px,
   PSNR added to the gate (`QualityGate`).
7. "Video colour metadata changed" rejected an already-HEVC source because the pipeline correctly converts full-range
   to limited-range; real phone videos would be affected. Fixed: tag equality removed (HDR still refused upfront; pixel
   comparison catches visible shifts).
8. Lossless JPEG stored coefficients as `int[64]` objects: 50 MP photo hit OutOfMemoryError. Fixed: flat `short[]`
   per component, memory guard, `largeHeap`.
9. Videos were copied in full to private storage first (a 4 GB video needed ~7 GB free). Fixed: read in place when the
   file is byte-identical to the original (hash-checked), so only the output needs space.
10. Trashed originals keep using storage ~30 days with no way to free it. Added **Free up space now** (permanent delete
    after re-verifying each compressed copy).
11. Rescanning offered already-compressed originals again. Fixed.
12. HEIC with EXIF orientation 6/8 would carry rotation only in EXIF (HEIC viewers use `irot`). Fixed: native rotation +
    upright EXIF; verified the decoder shows 750x1000 for a rotated source. HEIC quality could not be tested here (see 1).
13. UI: review list pushed below the fold, Resume shown while running, "0% of current video" for photos, grey dialogs,
    no margins on Compare, "1 files". Fixed.
14. SSIM was ~8x slower than needed (2.5 s per 12 MP compare). Rewritten with 4x4 block sums; exact match to the
    reference implementation in new unit tests; PSNR added.

### Final device run (Smart, defaults)
| File | Before | After | Result |
|---|---:|---:|---|
| 1080p30 H.264 | 37.5 MB | 5.2 MB (13%) | FFmpeg all frames: SSIM 0.974, PSNR 43.1 dB; audio identical; date kept |
| no-audio 1080p30 | 36.9 MB | 4.6 MB (12%) | same |
| portrait (rot 90) | 22.2 MB | 13.5 MB (60%) | SSIM 0.979, PSNR 44.2 dB; rotation, date, audio kept |
| already HEVC | 13.0 MB | 4.7 MB (36%) | SSIM 0.989, PSNR 45.3 dB |
| photo1/3/5 (4:4:4) | 6.5/5.5/5.4 MB | 47/42/45% | PSNR 47–48 dB; EXIF (date, GPS, orientation) identical |
| photo2/4 (4:2:0, rot 6/8) | 2.9/3.6 MB | 80/84% | PSNR 47–49 dB; EXIF identical; Gallery orientation 90/270 |
| 50 MP | 14.0 MB | 95% | Lossless fallback, pixel-identical |
| 1080p60, 4K30 | | skipped | tablet encoder limit, clear reason shown |
| **Total** | 147.5 MB | 54.6 MB | 92.9 MB saved |

Workflow checks (all passed): permission flow; scan/review; batch Trash approval of 10 items in one system dialog;
compressed copies renamed to the original names with original file times; Compare (video and rotated photo);
Restore (original back byte-identical by SHA-256, copy removed); Free up space now (10 originals permanently deleted,
device free space +144 MB); force-stop during video encoding (originals byte-identical, no partial output in the
Gallery, temp cleaned, Resume completed all files); low battery (15% simulated, "Paused · battery below 20%", resumed
automatically after reset); rescan excludes compressed originals; no entries in `logcat -b crash`.

Not verified: POCO F5 (Qualcomm) HEIC quality behaviour and 4K/60 fps encoding; thermal pause; Android 6-hour service
limit; libraries with thousands of files.

## 2026-10-03 — POCO F5 (Android 15, HyperOS 3, Snapdragon 7+ Gen 2) over wireless debugging

Encoders: `c2.qti.hevc.encoder` (VBR only, up to 4096 px, 1080p60 and 4K30 supported; CQ variant limited to 512 px)
and `c2.qti.heic.encoder` (CQ, quality honoured).

- **Photos:** HEIC works. Generated 12 MP JPEGs: rotated (orientation 6/8) → HEIC at 13–17% of original size,
  PSNR ≈ 46 dB, rotation verified via the decoder (750x1000). A 4:2:0 landscape photo → JPEG at 66%.
- **Encoder selection bug fixed:** the last matching codec (legacy OMX) was chosen; now the first (Codex2) is used.
- **Owner's real video (1080p portrait, 25.5 Mbps, fast handheld motion over textured ground):** the 0.2/1.0 engine
  skipped it (PSNR 34.9 dB). Diagnosis on a private copy: alignment correct (±3 frame search), no brightness/colour
  shift; detail genuinely lost in fast-motion frames. Bitrate sweep: 43% size → worst frame SSIM 0.857; 61% → 0.900
  (visible smearing at 2x zoom); 75% → 0.914; 85% → 0.914 (saturated). B-frames: no gain (reverted).
  Conclusion: correct refusal. Changes: Smart ladder extended to 0.10/0.14/0.20/0.27 bpp, early stop when PSNR improves
  < 0.7 dB between attempts, and a plain-language reason ("Fast motion or fine detail: a smaller copy would visibly
  lose detail. Original kept."). Re-run on the copy: kept original after 68 s.
- Encoding speed on POCO: ~10 s for a 50 s 1080p clip per attempt.
- Owner's original verified unchanged (SHA-256 before/after); private copy removed with the QA app.

## 2026-10-03 — POCO F5 regression investigation and 1.0.1

The previous PSNR-plateau early stop and target-bitrate cutoff could reject compressible videos. Diagnosis used the owner's explicit permission for two temporary QA copies; no camera original was selected for compression, moved, or modified. The original SHA-256 fingerprints matched before and after. All temporary phone copies and the QA app were removed after testing.

| POCO F5 fixture | Smart/Max result | Evidence |
|---|---|---|
| Generated 8 s 1080p30 AVC motion clip | Smart output 30% of source size | Mean SSIM .995, worst .994, PSNR 45.6 dB; full engine pass |
| 3.5 min real 1080p landscape (private copy, 519,692,609 B) | **Smart output 298,034,461 B (57%)**, 43% saved | VBR 0.10: mean/worst .969/.948, PSNR 38.5; 0.14: .971/.952, 39.0; **0.20: .976/.962, 40.2**. Full engine accepted the 0.20 attempt after video timing, audio digest, rotation and duration checks, 130 s. Old code incorrectly stopped after the 0.14 attempt because aggregate PSNR improvement was only 0.5 dB. |
| 50 s real 1080p portrait fast-detail (private copy, 162,027,176 B) | Smart correctly skips; **Max output 121,661,445 B (75%)**, 25% saved | Smart at 0.27 bpp had mean/worst .958/.914, PSNR 37.3, so it fails Smart. Revised Max engine accepted its final 0.27 bpp attempt (mean .9583) after structural and audio checks, 82 s. Max can visibly soften detail. |

Release 1.0.1: `tools/test.ps1` PASS (75 core assertions, 60 adversarial JPEG cases); signed APK verification PASS, 102,949 B, SHA-256 `F6C0371BDF2E7147E68322BCAECFD7AF7F1899F96A002EB53A129FBB99CFC928`; installed as versionCode 4 on the POCO. `logcat -b crash` showed no crash. After the owner unlocked the phone, a read-only scan showed 2,557 candidates and zero selected; the Review screen visibly rendered thumbnails beside video filenames. Compare's compressed-video-first playback is implemented but was not exercised through the on-screen UI because no personal video was published during testing. The owner should visually inspect any Max output before Trashing its original.

## 2026-10-03 — Fourth owner video on POCO F5

The owner reported another Smart skip on the installed 1.0.1 build and explicitly approved a temporary private QA copy. The Report showed a generic fine-detail warning. Source: 413,095,773 B, AVC 1920×1080, 24 fps, rotation 270°, 132 s. The copied source and unchanged camera original had the same SHA-256 (`e559b158…447a317bd01899`) before and after testing. No camera file was selected for compression, replaced, moved, or deleted.

| Qualcomm HEVC encoder budget | Output as % of source | Mean / worst sampled-frame SSIM | PSNR |
|---|---:|---:|---:|
| Codec2 0.10 bpp | 25% | .899 / .894 | 36.1 dB |
| Codec2 0.14 bpp | 35% | .913 / .907 | 37.0 dB |
| Codec2 0.20 bpp | 50% | .926 / .923 | 37.8 dB |
| Codec2 0.27 bpp | 68% | .938 / .933 | 38.9 dB |
| Codec2 0.34 bpp | 85% | .946 / .940 | 39.7 dB |
| Legacy OMX 0.27 bpp | 68% | .938 / .933 | 38.9 dB |

The full Smart engine correctly kept the original after 141 s; even the extra 85%-size attempt failed Smart's .96/.95/40 gate, and the alternative encoder had no gain. This is not a codec crash or another early stop. The full Max engine passed at 278,897,353 B (68% of source; 32% saved) with mean SSIM .9384 after structural/audio checks, taking 172 s. At smaller sizes, a sampled frame visibly softened facial/fabric detail; Max should be used only with Keep originals and user comparison. The original remained hash-identical. The QA app, temporary device copy, and local diagnostic frames were removed; `logcat -b crash` was empty.

Product correction: Review had shown ≈80 MB for this 394 MiB video using the 0.10-bpp best case. That was a poor prediction of quality-verified output. In 1.0.2, video estimates are displayed as a best-case-to-85% range **or skip**, quality failures include measured scores, and the Report offers an opt-in Max retry that always keeps the original. Host tests passed (75 core assertions, 60 adversarial JPEG cases); signed APK verification passed, SHA-256 `7F8ADBA46467C25D4DA932FD4F5945B8F19F431DF760B752A8A44C0419717899`. VersionCode 5 installed on the POCO without clearing app data. On-phone UI check: the Report showed **Try Max · keep original** for the Smart skip; its warning dialog explicitly said nothing would move to Trash, and was canceled without starting work. A read-only rescan showed 2,557 candidates, zero selected, and the fourth video's range `79.8–334.9 MB or skip`. No service or crash entry appeared. Max publishing, Gallery playback and comparison still need an owner-approved trial on a copy, not an original.

Post-release end-to-end QA (owner approved temporary QA media access): generated `CompactTest_motion.mp4` (8 s, AVC 1080p30, no audio) was added to Camera. Compact QA's scanner showed exactly that one selectable item, excluding all owner media. With Smart + Keep originals, the foreground service completed 1/1; Report said DONE/kept both and showed **26,291,027 B → 7,778,493 B** (70.4% smaller), SSIM .99504. The generated source SHA-256 stayed `f71f95ca…ddadabadf4`. Compare visibly opened the compressed video and switched to the original. `logcat -b crash` remained empty. The two generated Camera files and QA app were removed; temporary QA screenshot was removed. This fixture had no audio, so UI sound playback remains unverified, although real-video engine tests above verified copied audio digests. The Max retry's confirmation dialog was verified on release 1.0.2 but not actually started on an owner video.
