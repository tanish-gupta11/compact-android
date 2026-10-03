# Compact decisions

## 2026-10-03 — Milestone 1

- The first build uses only Android framework APIs and does not claim HEIC encoding is available. The specified `androidx.heifwriter` AAR is not present in the workspace, and downloading a large dependency requires owner approval. Smart photo output therefore starts with a JPEG-safe fallback in the implementation; HEIC remains a planned adapter.
- No publishing, trashing, replacing, or deleting media is enabled in the scaffold. The scanner is read-only until the replacement pipeline has independently verified output and device-tested MediaStore trash behavior.
- The app targets Android 11+ (`minSdk 30`) as required by the system trash API.

## Implementation corrections

- HEIF writer 1.1.0 was fetched from Google Maven (53,493 bytes). The earlier large-download justification was incorrect. Apache-2.0 license is included under libs/heifwriter-1.1.0/META-INF/androidx/heifwriter/heifwriter/LICENSE.txt.
- ACCESS_MEDIA_LOCATION and setRequireOriginal are required to preserve unredacted GPS. Missing access blocks processing rather than silently stripping GPS.
- SSIM is a sampled/perceptual metric, not a guarantee of invisible change. Smart/Max are lossy. UI explains this explicitly.
- System trash retention is controlled by Android/OEM. Trashed originals continue using storage until permanently removed or expired; report distinguishes smaller copies from immediately freed storage.
- Physical device is currently absent. Continue implementation and host tests; do not declare device milestones passed.
- Private input snapshots add temporary storage cost but prevent a concurrently edited source from being replaced. Hash source again before requesting trash.

## Completed implementation scope and testing boundary

- The initial read-only scaffold restrictions above are historical; the 0.2 implementation now includes engines, publishing, user-approved Trash and restore.
- The owner explicitly authorized continuing without device testing. Host tests and APK checks are run, but device-gated milestone evidence is deferred, not marked passed.
- Screens share one main activity with a dedicated Compare activity; the product's screen flows remain available. Review uses a virtualized list. No additional UI dependency is introduced.
- Photos above the available heap budget are skipped at full resolution; encoding does not silently downsample. Comparison previews are limited to 2048 px, while photo quality verification is full resolution.
- Video first attempts use hardware CQ when supported; retries use the specified higher-bitrate VBR. Unsupported encoder configurations fail closed.
- MP4 location uses MediaMuxer's precision; verification tolerates at most 0.0001 degrees. Photo GPS remains in copied EXIF bytes.
- Pending outputs are journaled before insertion, hashed after writing, and reconciled after interruption. Original/copy hashes are rechecked before asking for Trash. Restore renames the owned compressed copy out of the way before Android restores the original, avoiding a filename collision.
- A private-source snapshot plus best/candidate outputs increases temporary storage needs over the original estimate. The UI pauses when the required temporary budget is unavailable.
- Pure-Java code is formatted and committed independently of private signing material and APKs.

## 2026-10-03 — 1.0 review and device fixes

- Quality gate is PSNR + SSIM (`QualityGate`), measured at full resolution (photos) or up to 1920 px (video frames).
  Smart: PSNR ≥ 40 dB, SSIM ≥ 0.97 photos / 0.96 video, worst tile or frame ≥ 0.95. Max: 36 dB, 0.94/0.93, 0.91.
  Rationale: on grainy camera content SSIM penalises smoothed grain that is not visible; PSNR ≥ 40 dB is visually
  transparent. Verified independently with FFmpeg and side-by-side crops.
- Photo order: HEIC (if the encoder honours quality) → JPEG quality search (93/90/95) → Lossless fallback (≥ 2%).
- Videos: CQ first, then VBR 0.10 and 0.14 bits/pixel (Smart). Skip only when a re-encode cannot save 15%.
- Videos are read in place when byte-identical to the original; photos keep a private snapshot (GPS-unredacted read).
- Added permanent "Free up space now" for trashed originals, because Trash does not free storage for ~30 days.

## 2026-10-03 — POCO F5 video retry correction (1.0.1)

- Removed the aggregate-PSNR early stop. On a real 3.5-minute POCO camera video, PSNR rose only 0.5 dB between early attempts, but the next attempt passed Smart at 57% of the original size. The average cannot predict whether the worst sampled frame will pass.
- Removed the pre-encode bitrate cutoff. Qualcomm VBR undershoots the requested bitrate; only the verified *actual* output size determines whether the 15% saving floor is met.
- Max includes a 0.27 bpp attempt. A real fast-motion clip failed Max's worst-frame floor at 0.20 bpp but passed at 0.27 bpp with 25% savings. Smart quality rules remain unchanged.

## 2026-10-03 — POCO F5 real-library expectations (1.0.2)

- Smart keeps its verified image-quality thresholds. A fourth real 1080p AVC clip failed them even at 85% of source size, and the alternative Qualcomm encoder gave identical scores. Relaxing Smart merely to return an output would break the promised quality choice.
- Max remains explicitly opt-in. Report offers a retry for Smart-skipped videos with `trash=false` forced, so users can compare any Max copy while the original stays untouched.
- Review's bitrate-derived number is a best-case lower bound, not a likely final size. Videos display a range up to the 85% acceptance ceiling or “skip”; home and selection totals are labeled best-case. This preserves the required bitrate estimate without implying all videos can reach it.
- Quality-gate skips now retain their measured SSIM/PSNR in Report instead of a generic “fast motion” explanation. No analytics or network permission are added.
