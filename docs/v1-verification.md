# Pod4Me 1.0.0 verification

Source version: 1.0.0, code 8. Database version: 4. Audit baseline: `audit.txt`.

## Audit disposition

| Findings | Change | Evidence / remaining check |
| --- | --- | --- |
| F1–F2 | Lightweight snapshots, lazy notes, targeted progress updates, remembered list order, indexed lookups/counts, player-only position recomposition, foreground ticker, storage refresh on download changes | 10,001-episode / 20 MB notes regression; unaffected episode identities and catalogue revision retained. Phone profiling remains pending. |
| F3–F5 | Explicit RFC date offsets, UTC fallback, safe enclosure escaping, namespace-aware precedence, skipped-entry warnings | JVM regressions; Android equivalents added, execution pending. |
| F6–F8, F13 | Same-item seeks save only the new position; reset routed through service with progress epochs; measured duration wins; cold session remains unloaded; playlist-change discontinuities excluded from progress saves | Store and Media3 service regressions. Real audio/device timing remains pending. |
| F9, F12 | Up to five previous source items, artwork metadata, secondary ±10-second slots, media-button receiver and resumption callback | Service test checks previous item availability. One UI controls and Bluetooth resumption pending. |
| F10 | Streaming portable metadata backup and transactional merge restore; no cloud backup | Round trips, clean-library remapping, repeated restore, invalid input rollback, runtime-field rejection and shared-audio restore regression. |
| F11, F14 | Completed download ID retained until deletion; readable download/constraint/link errors | DownloadManager shadow tests cover success, repeat completion, cancellation, removal, failure and deletion. Actual Android manager behavior pending. |
| F15–F16 | Skip unchanged metadata writes; preserve history and heard status; Starred stays selected when sort changes; integer GB retained | Trigger-based update regression; documented history and parser limitations. |
| F17 | Explicit expiry validation, SQL statements per line, versioned User-Agent, modern Kotlin compiler options, cached date formatters, keyed dialog input, navigation icons, single activity instance, unused queue function removed | Full Kotlin/Java source compilation; core/schema checks. |
| F18 | Versioned, non-debuggable release configuration and guarded signing script; preserves prior certificate and checks old APK certificate before delivery | Normal-terminal Gradle build, release lint and signed APK checks passed. Git commit/push remain pending because this workspace mounts `.git` read-only. |

## Executed in this workspace

- Dependency-free Java checks: **96 passed** after the follow-up correction.
- SQLite/schema/XML checks: **10 passed**.
- Full Java/Kotlin/Compose source compilation with cached Kotlin 2.2.21, Android API 36 and dependency jars: passed. Temporary BuildConfig matched version 1.0.0; resource identifiers came from the previous Gradle resource build.
- JUnit/Robolectric regressions: **35 passed** (1 core suite, 7 suggestion tests, 22 store/backup/download tests, 3 migration tests and 2 Media3 service tests). These were launched directly with cached dependencies because the Gradle wrapper cannot write its lock in the read-only shared Gradle directory. The direct run uses current source/assets/manifest and the existing resource APK; it does not replace the normal Gradle/lint/release checks.
- The initial 10,001-episode / 20 MB show-notes test measured approximately 14 ms for one progress update on this machine. This verifies the data path, not phone UI performance.
- Shell syntax and `git diff --check`: passed.

## Release build and independent APK verification

The user ran `./tools/build-apk.sh` from the normal terminal on 2026-10-29. The observed log `/tmp/pod4me-v1-build.log` ended with `BUILD SUCCESSFUL in 39s (latest rebuild)` and the script's verified-APK message. All 35 Gradle Android unit tests passed with zero failures/errors; release lint had zero errors and 20 warnings (dependency update notices, KTX suggestions, the media service's required export, and a redundant resource qualifier). Android device-test APK compilation passed; tests were not run on hardware.

Independent checks of the delivered file:

- APK: `dist/Pod4Me.apk` and identical versioned copy `dist/Pod4Me-1.0.0.apk`.
- Application ID `com.pod4me`; version name `1.0.0`; version code `8`; min API 34 / target API 36.
- Non-debuggable; APK signature verification passed (v2 scheme).
- Signing-certificate SHA-256: `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada`, matching the previous local APK captured before replacement.
- Size: 28,829,095 bytes.
- APK SHA-256: `930c41c5a2f467980c0e86f63be44ff9f9d238b23f58ffbe96fe2f5b5067cc87`.
- The certificate is retained from the earlier debug-signed personal builds to allow an update; the release APK itself is not debuggable.

## Remaining device acceptance

Run the connected tests and [device checks](device-checks.md) on the S24 / One UI 9. No device results have been claimed. Bluetooth resumption, audio focus, lock-screen button placement, actual DownloadManager behavior and upgrade preservation need a real device.

Gradle dependencies remain pinned; no dependency upgrade was bundled into this release. The resumption implementation follows [Media3 background playback guidance](https://developer.android.com/media/media3/session/background-playback), using the callback signature provided by the pinned Media3 1.8.0 API.

## Follow-up: default RSS namespace and shared-feed restore

The default-namespace RSS rejection was reproduced with a failing regression before the parser was changed. Standard RSS elements now match the namespace of the RSS root, including channel/item, metadata, enclosures and artwork. Other namespaces remain isolated; Atom still requires its own namespace.

The shared-feed restore fix was already present in the verified v1 build: a missing feed with an empty URL is inserted with ID `shared`. Its regression was strengthened to assert that ID explicitly after an empty-library restore and to start a subsequent shared download.

After this correction, 96 core checks and all 35 JUnit/Robolectric tests passed via the cached standalone toolchain. Android device-test sources compiled, including the new namespace regression; execution on a device remains pending. `git diff --check` passed. The normal-terminal rebuild subsequently passed all 35 Gradle unit tests, release lint (zero errors), release assembly and device-test compilation. Independent version/signature/non-debuggable/checksum checks passed. The APK/hash recorded above now identify the corrected release.
