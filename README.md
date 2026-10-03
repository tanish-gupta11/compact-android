# Compact

Compact is an offline Android app that makes smaller camera-photo and video copies. Smart and Max are **lossy**:
automated checks reject many degraded results, but cannot guarantee every scene looks the same to you. Never
permanently delete an irreplaceable original without checking the copy and keeping a separate backup.

**Version 1.0.4** adds Gallery sharing. Earlier compression versions were device-tested on a Samsung SM-X115 and POCO F5 with generated `CompactTest_*` media; see
`TEST_REPORT.md` for passes and remaining limitations. Real POCO videos showed that Smart may correctly skip
difficult footage and Max may visibly soften detail.

The later **1.0.3 scrolling-fix build** keeps the same Android version code/name (6 / 1.0.3). It makes the Review
file list itself scroll, with the filters as its header. Check the APK SHA-256 in the release page to distinguish
it from the earlier 1.0.3 binary.

## What it does

- **Smart (default):** videos → H.265 with the phone's hardware encoder, audio copied bit-for-bit; photos → HEIC
  when the phone's HEIC encoder is tunable, otherwise JPEG. Each file is searched for the smallest version that passes
  the quality gate (PSNR ≥ 40 dB and SSIM floors), measured at full resolution.
- **Lossless:** repacks JPEGs with optimal Huffman tables; every pixel stays identical (typically 3–15% smaller).
  Smart also falls back to this automatically when a lossy copy would not save at least 15%.
- **Max saving:** looser gate (PSNR ≥ 36 dB) for more space.
- Checks resolution, frame rate, rotation, date, GPS and photo EXIF before accepting a result. If the phone's
  media provider cannot preserve a required date, Compact rejects that output and keeps the original.
- Originals go to Android's system Trash only after you approve; **Recently replaced** restores them, or
  **Free up space now** permanently deletes them (Trash otherwise keeps using space for about 30 days). Version
  1.0.3 lets you review a kept result and request Trash for just that original after both files are rechecked.
- Pauses safely on low battery, heat or low storage; survives being killed mid-file (originals untouched).
- No internet permission, accounts, ads or analytics.

Measured on the test tablet (generated camera-like media): 1080p H.264 videos 86–88% smaller, portrait video 40%,
already-HEVC video 64%; 12 MP photos 53–58% smaller (4:4:4 JPEG) or 16–20% (4:2:0 JPEG); PSNR 43–49 dB, i.e. visually
identical. Real savings depend on your camera's files.

## Install and test on the POCO F5

To compress a video you find in Gallery, tap **Share → Compact**. You can share one video or select several first.
Compact opens those local videos selected in Review, including files outside Camera. Review the list, tap Compress,
choose your mode and after-action, then tap Start. It may ask for video access so queued work can continue in the
background. Compact does not start compression just because a video was shared. Cloud-only or ambiguous shares
that cannot be matched to a local original are explained rather than added to the queue.

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
- Video sizes in Review are a **best-case bitrate estimate through an 85%-of-original ceiling, or skip**. The
  encoder may need substantially more data to preserve detail than the optimistic estimate suggests. A fourth real
  POCO clip was skipped in Smart even at an 85%-size test output; Max saved 32% on a private copy. For a Smart skip,
  Report offers **Try Max · keep original**. This never Trashes the source; compare picture and sound before keeping
  the result. The Report shows the exact saved path and capture date. After checking the copy, you can request
  system Trash for that one original. The original remains recoverable only while Android retains it; **Free up
  space now** is irreversible.
- On the POCO F5, an artificial QA JPEG with a manually seeded nonzero Gallery date was re-indexed by HyperOS with
  `DATE_TAKEN=0` when published. Compact rejected the copy and retained the original. Do not assume every photo
  format/date combination works merely because video tests pass.

## Build and tests

```powershell
.\tools\test.ps1          # 78 assertions + 60 lossless-JPEG round trips (desktop JVM)
.\tools\build.ps1         # signed releases\compact.apk
.\tools\build.ps1 -Qa     # build\compact-qa.apk: debuggable, only touches CompactTest_* files
.\tools\verify-apk.ps1
```

On a connected test device, `adb shell am instrument -w com.compact.qa/com.compact.QaDiag` runs the real engines on
`CompactTest_*` files placed in the QA app's `cache/diag` folder and prints sizes, SSIM and PSNR.

Uses JDK 21 and the local SDK at `..\.android-tools\sdk`; no Gradle or NDK. The signing key is in the ignored
`signing/` folder (password protected by Windows DPAPI). Back it up; updates must be signed with the same key.
