# Prompt to start the build (paste into Codex or any coding agent)

Open the agent in this folder first:
`C:\Users\Dads Gift(Razon)\Documents\ChatGPT\apps\compress`

---

You are a senior Android engineer. Build "Compact", a production-grade offline Android app that frees storage by
re-compressing camera photos and videos with no visible quality loss. The default "Smart" mode re-encodes videos
to H.265 and photos to HEIC at full resolution, and verifies every file against its original with SSIM before
replacing it.

Before writing any code, read these files in this folder completely: AGENTS.md (your rules), BUILD_SPEC.md (the
authoritative technical spec) and PLAN.md (background). Also study the sibling project ..\vault, especially
tools\build.ps1, Ui.java, BaseActivity.java and the -Qa build variant, and reuse those patterns.

Then:
1. Summarise your understanding in 10 lines and list any spec problems you see. Record decisions in DECISIONS.md.
2. Build milestone by milestone exactly as listed in AGENTS.md. After each milestone, run the relevant tests,
   show me the evidence (test output, file sizes, SSIM numbers, QA screenshots) and commit.
3. Test on the connected Samsung tablet using only media you create named CompactTest_*. Never touch my own photos
   or videos. Ask me when you need me to unlock the tablet or tap the camera shutter.
4. Never claim something works without running it. Put every result, including failures, in TEST_REPORT.md.
5. Finish with: signed releases\compact.apk, README.md with a step-by-step checklist for my POCO F5,
   THIRD_PARTY.md, all tests passing, test media and the QA app removed from the tablet, and a final report of
   what works, what was not verified, and why.

Quality bar: this app rewrites irreplaceable family photos and videos. Safety rules in BUILD_SPEC §1.3 are
non-negotiable. When in doubt, skip the file and keep the original.
