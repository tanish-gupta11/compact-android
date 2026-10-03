# Third-party code

The app uses Android framework APIs plus **AndroidX HeifWriter 1.1.0**, Apache License 2.0, copyright The Android Open Source Project.

- Official artifact: https://dl.google.com/dl/android/maven2/androidx/heifwriter/heifwriter/1.1.0/heifwriter-1.1.0.aar
- Artifact size: 53,493 bytes.
- SHA-256: `6DC4D498E2763A626C04841E440529846CA50C872C81811AC05B51F0B7451C63`.
- Vendored binary: `libs/heifwriter-1.1.0/classes.jar`.
- Full license: `libs/heifwriter-1.1.0/META-INF/androidx/heifwriter/heifwriter/LICENSE.txt`.
- The same license is packaged as `assets/heifwriter-LICENSE.txt` and available from Settings.

No other third-party library is included in the APK. Optional Java formatting during development used Google Java Format 1.22.0 under the ignored build directory; it is not shipped.

The compiler reports a missing AndroidX annotation enum (`RestrictTo.Scope`) from the vendored AAR's class-retention annotations. Dependency inspection found no external executable AndroidX dependencies, and APK DEX packaging succeeds. Device runtime remains unverified.
