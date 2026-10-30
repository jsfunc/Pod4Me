# Pod4Me verification

Current source version: 1.0.1, code 9. Database version: 4. Audit baseline: `audit.txt`. Earlier sections below preserve historical 1.0.0 evidence; the final section records the current search release.

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
| F18 | Versioned, non-debuggable release configuration and guarded signing script; preserves prior certificate and checks old APK certificate before delivery | Normal-terminal Gradle build, release lint and signed APK checks passed. Base v1 is committed as `1c2a6d0`; the earlier layout/documentation changes are committed as `9bc33e0`; the new Downloads/Feeds changes are in the working tree. This workspace mounts `.git` read-only. |

## Executed in this workspace

- Dependency-free Java checks: **96 passed** after the follow-up correction.
- SQLite/schema/XML checks: **10 passed**.
- Full Java/Kotlin/Compose source compilation with cached Kotlin 2.2.21, Android API 36 and dependency jars: passed. Temporary BuildConfig matched version 1.0.0; resource identifiers came from the previous Gradle resource build.
- JUnit/Robolectric regressions: **35 passed** (1 core suite, 7 suggestion tests, 22 store/backup/download tests, 3 migration tests and 2 Media3 service tests). These were launched directly with cached dependencies because the Gradle wrapper cannot write its lock in the read-only shared Gradle directory. The direct run uses current source/assets/manifest and the existing resource APK; it does not replace the normal Gradle/lint/release checks.
- The initial 10,001-episode / 20 MB show-notes test measured approximately 14 ms for one progress update on this machine. This verifies the data path, not phone UI performance.
- Shell syntax and `git diff --check`: passed.

## Release build and independent APK verification

The user ran `./tools/build-apk.sh` from the normal terminal on 2026-10-29. The observed log `/tmp/pod4me-v1-build.log` ended with `BUILD SUCCESSFUL in 40s` and the script's verified-APK message. All 35 Gradle Android unit tests passed with zero failures/errors; release lint had zero errors and 22 warnings (dependency update notices, KTX suggestions, the media service's required export, a redundant resource qualifier, and two artwork-helper Modifier parameter conventions). Android device-test APK compilation passed; tests were not run on hardware.

Independent checks of the delivered file:

- APK: `dist/Pod4Me.apk` and identical versioned copy `dist/Pod4Me-1.0.0.apk`.
- Application ID `com.pod4me`; version name `1.0.0`; version code `8`; min API 34 / target API 36.
- Non-debuggable; APK signature verification passed (v2 scheme).
- Signing-certificate SHA-256: `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada`, matching the previous local APK captured before replacement.
- Size: 28,829,095 bytes.
- APK SHA-256: `11b7c441144cb1c53a9998a5ef0f88ff549f352f17f52dc84409ccbf8a48951a`.
- The certificate is retained from the earlier debug-signed personal builds to allow an update; the release APK itself is not debuggable.

## Remaining device acceptance

Run the connected tests and [device checks](device-checks.md) on the S24 / One UI 9. No device results have been claimed. Bluetooth resumption, audio focus, lock-screen button placement, actual DownloadManager behavior and upgrade preservation need a real device.

Gradle dependencies remain pinned; no dependency upgrade was bundled into this release. The resumption implementation follows [Media3 background playback guidance](https://developer.android.com/media/media3/session/background-playback), using the callback signature provided by the pinned Media3 1.8.0 API.

## Follow-up: default RSS namespace and shared-feed restore

The default-namespace RSS rejection was reproduced with a failing regression before the parser was changed. Standard RSS elements now match the namespace of the RSS root, including channel/item, metadata, enclosures and artwork. Other namespaces remain isolated; Atom still requires its own namespace.

The shared-feed restore fix was already present in the verified v1 build: a missing feed with an empty URL is inserted with ID `shared`. Its regression was strengthened to assert that ID explicitly after an empty-library restore and to start a subsequent shared download.

After this correction, 96 core checks and all 35 JUnit/Robolectric tests passed via the cached standalone toolchain. Android device-test sources compiled, including the new namespace regression; execution on a device remains pending. `git diff --check` passed. The normal-terminal rebuild subsequently passed all 35 Gradle unit tests, release lint (zero errors), release assembly and device-test compilation. Independent version/signature/non-debuggable/checksum checks passed. The APK/hash recorded above now identify the corrected release.

## Follow-up: artwork and bottom navigation layout

Feeds, Latest and Queue now use 72 dp-wide artwork spanning the full item content height, with text, metadata and actions to its right. The shared layout measures the content first and fits the artwork in the same pass, avoiding intrinsic measurement of tooltips. Library cards retain their previous code and 40 dp artwork. The bottom navigation omits visible labels and uses equal 64 dp button heights and 24 dp icons, while retaining accessible names.

The normal-terminal rebuild passed release assembly, all 35 unit tests, release lint (zero errors) and compilation of device tests. Independent APK version, certificate, non-debuggable and checksum checks passed; the size/hash above identify this latest layout build. S24 visual acceptance remains pending (device check 27).


## Follow-up: Downloads deletion and Feeds icon (2026-10-29)

Downloads now places a bin before Play/Queue/Details, reducing button spacing to 40 dp while retaining 24 dp glyphs. Deletion reuses the Details confirmation, retains metadata/history, and remains disabled for 4ever/current playback, including if protection changes while the dialog is open. Library artwork stays 40 dp. Feeds navigation uses a dedicated microphone vector without the launcher's empty padding.

`JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh` passed in 3m 38s with approved access to shared Gradle/signing files. The system Java 17 installation lacks a compiler, so the complete installed Android Studio JDK was used. Results: 96 core checks; 10 schema/XML checks; 35 JUnit/Robolectric tests, zero failures/errors/skips; release lint zero errors and 22 warnings; release assembly and Android device-test APK compilation passed. `git diff --check` passed. Device tests and visual acceptance have not been run.

**This build is not a compatible update for the historically recorded installation.** No previous APK was present in `dist/`, and the current machine's debug keystore copied by the canonical script has certificate SHA-256 `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`, which differs from the original certificate recorded above. The original keystore path has been requested; do not uninstall the installed app or replace its signing identity.

The generated artifacts were moved out of `dist/` to `app/build/outputs/signing-mismatch/`. This diagnostic APK is 28,846,059 bytes, SHA-256 `c9c8904d963c1dcf9fcbc350ea47ef390e2497d0f5af4ffdaaf53816b8c3e3bf`; its versioned copy is identical. Signature verification passed and the APK is non-debuggable, version 1.0.0/code 8. These facts verify the build, not upgrade compatibility. Build log: `/tmp/pod4me-download-controls-build.log`. Rebuild with the original signing key before delivery; device checks 19 and 28 cover this UI change.


### Accepted fresh installation with this computer’s key

The user subsequently accepted using a new key and stated that they are uninstalling the old version. This authorizes delivery of the regular `com.pod4me` APK for a fresh installation, not a separate Test app. A canonical rebuild with the Android Studio JDK passed in 9s (118 tasks up-to-date, one executed). It reran 96 core and 10 schema/XML checks; Android unit-test, lint and assembly results were up-to-date from the successful full build above.

Delivered artifacts: `dist/Pod4Me.apk`, identical `dist/Pod4Me-1.0.0.apk`, and their checksum files. APK size and hash remain 28,846,059 bytes and `c9c8904d963c1dcf9fcbc350ea47ef390e2497d0f5af4ffdaaf53816b8c3e3bf`. Independent APK signature verification passed with certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`. Preserve this key for future updates. This supersedes the earlier decision to withhold delivery, but does not make the APK compatible with the old certificate. No phone installation, backup/restore or UI test has been confirmed.


## Version 1.0.1/code 9: ranked search inside a feed (2026-10-29)

The feed Browse heading now has a search button. Search opens a focused field, updates after a 250 ms pause and orders results by descending score, then descending publication date and ID. Every distinct query word must match in the title or description, in any order. Case and accents are folded; exact/title matches are weighted above weaker partial/fuzzy matches. Partial words start at three characters; one-letter insertion/deletion/replacement/transposition tolerance starts at four. Queries are limited to 256 characters. Clear/close restores publication order. Synonyms are omitted; no external search service, model or download is used.

All locally retained metadata in the selected feed is included, whether audio has been downloaded or not. HTML description text is indexed without HTML attributes. A temporary word index is built on IO in 32-row database batches; parsing occurs outside the store mutex. Scoring runs on Default and cooperatively checks cancellation. Feed/revision/query composition keys cancel superseded work and prevent stale results. Progress ticks do not reload descriptions. The normal lightweight library snapshots, schema 4 and backup format 1 remain unchanged.

Canonical build using the Android Studio JDK completed in **1m 29s**. **96 core checks, 10 schema/XML checks and all 45 JUnit/Robolectric tests passed**, with zero failures/errors/skips. Ten new tests cover normalization, language-independent case folding, ranking/ties, typos, partial and short terms, multiword matching, repeated words, 10,000 episodes, HTML descriptions, feed isolation, undownloaded episodes, batched indexing and metadata refresh/retained history. Release lint: **zero errors, 22 warnings**. Release assembly and Android device-test compilation passed. `git diff --check` passed. Build log: `/tmp/pod4me-feed-search-build.log`.

Delivered APKs: `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.1.apk`, **28,879,355 bytes**, SHA-256 `a07f16b447ed1e826552644144ffd564e6d26a900f009421d4ca30d3e886b4e4`. Independently verified version 1.0.1/code 9, non-debuggable status and APK signature. Signing certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f` matches the previous APK from this computer, so this is an update for that installation. No phone tests were performed; search UI, keyboard, responsiveness during playback, update preservation and device check 29 remain pending.
