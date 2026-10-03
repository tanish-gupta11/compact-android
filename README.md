# Compact

Compact is an offline Android app that frees storage by re-compressing camera photos and videos with no visible
quality loss, and only replaces an original after the smaller copy has been verified.

**Version 1.0**: device-tested on a Samsung SM-X115 (Android 16) with generated `CompactTest_*` media; see
`TEST_REPORT.md`. Not yet run on a POCO F5; use the checklist below first.

## What it does

- **Smart (default):** videos → H.265 with the phone's hardware encoder, audio copied bit-for-bit; photos → HEIC
  when the phone's HEIC encoder is tunable, otherwise JPEG. Each file is searched for the smallest version that passes
  the quality gate (PSNR ≥ 40 dB and SSIM floors), measured at full resolution.
- **Lossless:** repacks JPEGs with optimal Huffman tables; every pixel stays identical (typically 3–15% smaller).
  Smart also falls back to this automatically when a lossy copy would not save at least 15%.
- **Max saving:** looser gate (PSNR ≥ 36 dB) for more space.
- Keeps resolution, frame rate, rotation, date, GPS and all photo EXIF. Gallery order is unchanged.
- Originals go to Android's system Trash only after you approve; **Recently replaced** restores them, or
  **Free up space now** permanently deletes them (Trash otherwise keeps using space for about 30 days).
- Pauses safely on low battery, heat or low storage; survives being killed mid-file (originals untouched).
- No internet permission, accounts, ads or analytics.

Measured on the test tablet (generated camera-like media): 1080p H.264 videos 86–88% smaller, portrait video 40%,
already-HEVC video 64%; 12 MP photos 53–58% smaller (4:4:4 JPEG) or 16–20% (4:2:0 JPEG); PSNR 43–49 dB, i.e. visually
identical. Real savings depend on your camera's files.

## Install and test on the POCO F5

1. Copy `releases/compact.apk` to the phone and install it (Android 11+). Keep a separate backup of anything precious.
2. Start with copies: a few videos (1080p and 4K, one portrait) and about 10 photos.
3. Open Compact → **Scan camera library** → allow **Allow all** photo/video access.
4. Select the copies → **Compress N files…** → keep **Smart** and **HEIC**. For the first run choose
   **Keep originals alongside compressed copies**. Allow location-metadata access so GPS is kept.
5. When it finishes, open **Reports** → **Compare** a few results. Check them in the Gallery too: sharpness, colours,
   rotation, date/order, location, video sound and sync.
   Review shows thumbnails; tap one to preview the source before selecting it. For a successful video, Compare plays
   the compressed copy first and can switch to the original at the same position.
6. Note in the report whether photos came out as `image/heic` (HEIC works on this phone) or JPEG.
7. Then try the default mode on a few disposable copies: **Review & approve Trash**, check the Gallery, open
   **Recently replaced**, **Restore original** on one, and use **Free up space now** on the rest.
8. For long runs, set Compact's battery setting to **No restrictions** in HyperOS app settings (the app never changes
   phone settings itself). Compression pauses below 20% battery unless charging.

## Limits

- Smart and Max are lossy by design; they are verified to look the same, not to be bit-identical. Use Lossless if you
  need identical pixels.
- Skipped and left untouched: HDR/10-bit video, RAW, existing HEIC, motion photos and Ultra HDR (gain-map) photos,
  wide-gamut photos, files the phone's encoder cannot handle at their resolution/frame rate, and files that would save
  too little. The report says why for each file.
- Photos larger than the phone's memory allows (for example some 50+ MP shots) get Lossless repacking only.
- Copies already in Google Photos, Mi Cloud or other backups are not touched.
- Some fast-motion or finely textured camera videos cannot meet Smart's quality check even after several retries.
  They remain unchanged. Max may save space on those videos with some visible softening; inspect the copy before
  allowing the original into Trash. A real POCO F5 test saved 43% on one 1080p video in Smart, while another was
  skipped by Smart and saved 25% in Max.

## Build and tests

```powershell
.\tools\test.ps1          # 75 assertions + 60 lossless-JPEG round trips (desktop JVM)
.\tools\build.ps1         # signed releases\compact.apk
.\tools\build.ps1 -Qa     # build\compact-qa.apk: debuggable, only touches CompactTest_* files
.\tools\verify-apk.ps1
```

On a connected test device, `adb shell am instrument -w com.compact.qa/com.compact.QaDiag` runs the real engines on
`CompactTest_*` files placed in the QA app's `cache/diag` folder and prints sizes, SSIM and PSNR.

Uses JDK 21 and the local SDK at `..\.android-tools\sdk`; no Gradle or NDK. The signing key is in the ignored
`signing/` folder (password protected by Windows DPAPI). Back it up; updates must be signed with the same key.
