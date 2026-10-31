# Pod4Me verification

Current source version: 1.0.11, code 19. Database version: 5. Audit baseline: `audit.txt`. Earlier sections below preserve historical 1.0.0 evidence; later sections record the orange theme and duration-label releases.

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


## Version 1.0.2/code 10: orange theme, episode actions and rating slider (2026-10-30)

Replaced the green theme and launcher background with an orange palette based on `#F54927`. Light-mode accents use `#B83216`; dark-mode accents use `#FFB4A1`, with warm surfaces, coordinated containers and matching platform/placeholder accents. Publisher artwork is untouched. Downloaded Library/Downloads actions are Delete → Details → Queue → Play; Latest/feed rows use Details → Queue → Play, or Details → Download/Cancel for missing audio. Details rating is a single horizontal row with a star/count and discrete 0–5 slider; zero clears, changes save on interaction completion, and accessibility exposes the rating and range. Compact geometry, deletion protection, schema 4 and backup format 1 remain.

Canonical build with the full system JDK 17 passed in **39s**: **96 core checks, 10 schema/XML checks, 45 JUnit/Robolectric tests, zero failures/errors/skips**. Release lint: **zero errors, 22 warnings**. Release assembly and Android device-test compilation passed. The initial build was interrupted by a service restart, then resumed successfully; the old recorded Android Studio JDK path is absent and the system JDK now includes `javac 17.0.20.1`. Existing compiler warnings concern the FeedSearch data-class copy visibility and the deprecated tooltip position API. No new test suite was added for these visual edits. An ad hoc numerical contrast check passed 12 text color pairs per mode (minimum 4.87:1 light / 5.52:1 dark); placeholder graphical contrast is 4.65:1 / 5.45:1. Old green literals are absent from app source/resources. `git diff --check` passed. No phone tests were executed; device check 30 is pending.

Independently verified non-debuggable APK, application ID `com.pod4me`, version **1.0.2/code 10**, signature valid. APKs `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.2.apk` are identical: **28,879,551 bytes**, SHA-256 `ecb3c84ed2805bb933db936345060c9a45aecb72c42e22ba95741efb2efb1b2c`. Certificate: `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada`. Log: `/tmp/pod4me-orange-build.log`.

The actual local keystores and pre-existing local APK use the original `6585…` certificate, despite the previous session’s notes recording `134a…`. The user confirmed their phone has the later 1.0.1 build; this APK therefore cannot update it. It was initially withheld in `app/build/outputs/orange-signing-mismatch/`. The user then stated that the 1.0.1 key is unavailable and explicitly authorized using another key, uninstalling the app and doing a fresh installation because no important information needs preserving. Delivery uses the existing persistent local key, not a newly generated key or a separate application ID. Preserve this key for future updates after the fresh installation. No completed uninstall/installation has been reported.

After the signing authorization, the canonical delivery rebuild passed in **6s** (118 tasks up-to-date, one executed), rerunning the 96 core and 10 schema/XML checks and retaining the verified unit-test/lint results. Delivered files were checked again: signature valid and identical hashes as above.


## Version 1.0.3/code 11: episode lengths in lists (2026-10-30)

The user clarified the available-episode list (Info/Download), then requested duration in minutes instead of file size. Latest, feed Browse and search rows now show a centered duration below their action buttons, e.g. `23min`. Downloaded Library rows replace the short expiry date with duration next to progress, e.g. `41% · 23min`, preserving `4ever` when protected. Expiry remains available in Details; storage and playback policies are unchanged. Display uses existing episode duration metadata, rounds to the nearest whole minute with a minimum one minute for positive values, and shows `Length unknown` for missing/nonpositive durations. No size lookup, extra network requests, file scans, database migration or backup-format change.

Final canonical build passed in **46s** using the full system JDK 17. **96 core checks, 10 schema/XML checks and 45 unit tests passed**, with zero unit-test failures/errors/skips. Release lint: **zero errors / 22 warnings**; its final report task reused the unchanged result. Release assembly and Android device-test compilation passed. No new tests were added for this display-only change. `git diff --check` passed. No phone tests were run; device check 31 covers placement, unknown/short/long durations, large fonts, measured durations and updating from 1.0.2.

Delivered `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.3.apk`: **28,879,551 bytes**, SHA-256 `aa5abb9d1e48333c263e5ffeec121f031feb02e491f17278a03063be49ab6031`. Independently verified version **1.0.3/code 11**, application ID `com.pod4me`, non-debuggable status and valid signature. Certificate `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada` matches the delivered 1.0.2, so no uninstall is needed when updating that installation. Log: `/tmp/pod4me-duration-build.log`.


## Version 1.0.4/code 12: silent refresh, scroll positions and darker launcher

Successful refreshes no longer produce a modal notice; failed refreshes retain a failure summary and feed-level errors. `ScrollPositions` retains live list/scroll states above navigation, saving item ID, index and pixel offset for Android UI restoration. Separate positions cover Library filters/sorts, global Latest, feeds/search queries, Feeds, Queue, Info, settings, Manage lists and Player. Async Info loading is kept separate from the saved notes scroll state. Latest tab selection retains its current feed/search. The launcher background changes to `#C43C1E` while the app palette remains unchanged.

Canonical build passed in **47s**: 96 core checks, 10 schema/XML checks, **50 unit tests**, lint zero errors / 22 warnings, release assembly and device-test compilation. Five new Robolectric regressions cover independent live views, stable anchors after insertion, removed-anchor fallback, repeated save/restore of unvisited views and separate Info/Player scroll positions. Signature, non-debuggable status and version 1.0.4/code 12 were independently verified. Intermediate APK: **28,895,935 bytes**, SHA-256 `6fd8595dc11fc9f532ee11a37f0a2b801b421f981b4e58d62894df8131441d81`; the versioned copy remains in `dist/Pod4Me-1.0.4.apk`. No phone tests. Log: `/tmp/pod4me-scroll-build.log`.

## Version 1.0.5/code 13: Apple discovery and queue playback recovery

New? follows Export in Feeds and starts an explicit directory request. The app searches three rotating topics derived from subscriptions/recent episode titles, combines related candidates with Apple's country chart, excludes subscriptions/duplicates, penalizes recently suggested shows, and returns up to ten results (normally seven related and three popular). Each text result explains its source and offers Subscribe plus an Apple Podcasts link. It does not copy Apple promotional artwork/audio. Public requests require no credentials or paid service configuration, use bounded 2 MiB responses, are spaced at least 3.1s apart and have a ten-minute in-memory cache (40 responses). No server, API key, AI service, automatic subscription or audio download. Fewer results/partial failures are reported honestly. This is a keyword heuristic and does not guarantee matching languages.

Live verification used generic public examples, not the user's library: Apple's podcast search returned valid podcast/RSS metadata; `https://itunes.apple.com/fr/rss/toppodcasts/limit=50/json` returned 50 chart entries; a batched lookup of two public IDs returned two podcast feed URLs. The newer marketingtools chart endpoint timed out, so the implementation uses the successfully verified iTunes RSS endpoint. Provider documentation: https://performance-partners.apple.com/search-api and https://podcasters.apple.com/support/3146-apple-podcasts-charts . Five discovery unit tests cover ten-result mixing, subscribed/duplicate exclusion, repeat avoidance, limited/empty-library results, topic extraction and rejection of unsafe/non-podcast directory entries.

Queue now has Play queue and per-downloaded-item Play, and an empty Player offers Play queue. A service-serialized Toggle starts queued audio when no current item exists, falling back to a valid saved item; missing audio yields an actionable error. External cold resumption shares that selection. Ended playback prefers a playable queued item before replaying the final episode. Real-file checks run on IO, timeline rebuilds are serialized and recheck the current item after suspension, and missing entries are skipped without being removed by this selection path. Existing expiry/deletion/reconciliation policies remain. Client custom-command failures are observed instead of ignored; buffering shows Preparing audio. Cold launch still loads nothing. Three new playback regressions cover a missing queued file before a playable item, idle Play with an invalid saved episode, and entirely missing queued files with preserved entries and an actionable error.

Final canonical build passed in **43s**: **96 core checks, 10 schema/XML checks and all 58 unit tests**, zero failures/errors/skips. Release lint **zero errors / 23 warnings** (the additional warning is a String.toUri KTX suggestion in the new Apple-link button); final lint report generation reused the unchanged report. Release assembly and device-test compilation passed. `git diff --check` passed. No phone tests were run.

Delivered files: `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.5.apk`, **28,961,471 bytes**, SHA-256 `4635de2e90c07a6b7599a9e87017ac3b80a2095436fd18202c6f5285f9aa39f0`. Independently verified `com.pod4me`, version **1.0.5/code 13**, non-debuggable status and valid signature. Certificate SHA-256 `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada` matches the delivered 1.0.2–1.0.4 releases and supports updating those installs without uninstalling. Log: `/tmp/pod4me-discovery-playback-build.log`. Real-device acceptance for scroll restoration, Apple discovery, queue/audio recovery and Bluetooth/system resumption remains pending.


## Latest-refresh episode highlights and download checks (2026-10-31, 1.0.6/code 14)

Newly discovered episode identities receive a locally persisted flag, replaced atomically on each successful refresh of that feed. Edited existing metadata is not new; failed fetches and unsubscribe races retain previous markers. Latest/feed Browse/search cards use a lighter background (white in light mode, `surfaceContainerHigh` in dark mode), with TalkBack state text. Library/Queue styling is unchanged. Existing episodes start unhighlighted after the explicit schema 4→5 migration; portable backups remain format 1 and exclude the local flag. Foreground pending-download reconciliation now runs every 10 seconds, as requested; completion broadcasts and launch reconciliation remain in place.

Regression coverage includes initial subscription, new backdated episodes, duplicate GUIDs, edits of older episodes, progress updates, process reopening, successful refresh without additions, retained history, per-feed independence, failed refresh and unsubscribe races. Upgrade tests cover schema versions 1–4 to 5 and preserve personal data. The unchanged-metadata trigger test now watches only metadata columns so clearing a refresh marker does not incorrectly count as rewriting notes.

The current environment differs from the previous release notes: Java 17 lacks its compiler; Android Studio JBR is present. The existing local APK and personal key use certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`, which must be preserved. This is not the other workspace’s 1.0.5 `6585…` certificate. No phone certificate or installation was verified and no uninstall was requested.

Library/Downloads now replaces header Refresh with the sort dropdown and removes its former dedicated row. Selected labels are shortened to fit a maximum 96 dp control without a “Sort:” prefix; open-menu labels and accessibility use full sort names. Existing sorting/filter/scroll-state behavior is reused. Other tabs retain Refresh. Device check 37 covers actual S24 fit and usability.

Final canonical build including all three requested changes passed in **1m 52s** using `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh`. Log: `/tmp/pod4me-new-episodes-build.log`.

- **Compilation:** release assembled; Android device-test APK compiled (not executed).
- **Automated tests:** 96 core checks, 11 schema/XML checks, 61 unit tests, zero failures/errors/skips.
- **Lint:** 0 errors, 23 warnings. Shell syntax and `git diff --check` passed.
- **Artifact:** `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.6.apk`, 28,978,097 bytes, SHA-256 `eca9fd42367280a5cb4a7ba57d21689757de762f3453f693714de97a6cbe64a3`. Independently verified version 1.0.6/code 14, non-debuggable, valid signature, and certificate `134a…` matching this workspace’s pre-existing APK.
- **Phone testing:** none; checks 35–37 cover visual highlighting, polling and header fit. The installed phone certificate remains unknown.


## Suggest feedback and fresh batches (2026-10-31, 1.0.7/code 15)

Suggest replaces New?; each recommendation has toggleable thumbs-up/down controls. Local persisted votes influence title/topic/publisher ranking and query topics. The last nonempty batch is strictly excluded, including after restarting; older suggestions can return, with a bounded 100-item recent-history penalty. Limited/error results never silently reuse the preceding batch. Shared preferences serialize disk writes off the UI thread and retain votes/history across app updates; these preferences are separate from episode stars and portable backups. Schema remains 5 and backup format remains 1.

Regressions cover strict exclusion under candidate scarcity and URL aliases, later return, generalized topic/publisher feedback, negative direct votes without permanent bans, feedback queries without subscriptions, genre parsing, persistent rating/history reload, vote switching/clearing, concurrent writes, an immediate read following a vote, and bounded history without discarding ratings. Social features are being discussed separately and are not implemented in this release.

Final canonical release passed in **1m 18s** with `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh`; log `/tmp/pod4me-feedback-build.log`.

- **Compilation:** release APK assembled and Android device-test APK compiled; device tests not executed.
- **Tests:** 96 core checks, 11 schema/XML checks, **69 unit tests**, no failures/errors/skips.
- **Lint:** 0 errors / 24 warnings (the new preferences editor adds one KTX suggestion). Shell syntax and `git diff --check` passed.
- **APK:** `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.7.apk`, version 1.0.7/code 15, 28,995,785 bytes, SHA-256 `48d90816dda629551e3e06036e976cf746ec1dc1647b14512c27e52b4010b7e3`. Signature valid, non-debuggable, certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f` matches this workspace’s prior 1.0.6.
- **Phone:** no tests performed; user plans to install/check. Discovery relevance and device check 38 remain pending. Social/WhatsApp changes remain discussion only.


## No-hosting sharing trial (2026-10-31, 1.0.8/code 16)

The user accepted a limited-audience trial using existing messaging apps and no Pod4Me hosting. Feeds now offers Open shared message with explicit Paste, bounded multiline input and local errors; it routes through the existing confirmation before subscribing/downloading. Shared messages include recipient instructions and feed titles. Legacy links are accepted. Regressions cover whole-message copying with a personal note/instructions, reserved characters, and legacy untitled feed links. No database, backup, accounts or hosted-service change. WhatsApp menus and two-phone convenience remain device check 39.

Final canonical build passed in **1m 34s**; log `/tmp/pod4me-sharing-build.log`.

- **Compilation:** release APK assembled and Android device-test APK compiled (device tests not executed).
- **Tests:** 96 core checks, 11 schema/XML checks and **71 unit tests**, zero failures/errors/skips.
- **Lint:** 0 errors / 24 warnings. Shell syntax and `git diff --check` passed.
- **Artifact:** `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.8.apk`, version 1.0.8/code 16, 29,012,169 bytes, SHA-256 `5e26992cb9a9d50130b3272d0321ae8d995345d1e070f3cb99ee099cfc5872e1`. Independently checked valid signature, non-debuggable status and certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`, matching this workspace’s 1.0.7.
- **Phone:** no testing performed. Actual WhatsApp menu availability, paste usability and two-phone delivery remain pending; the user will report trial results.


## Shorter shared messages (2026-10-31, 1.0.9/code 17)

After user feedback about the duplicated Radio France URL, outgoing messages now contain only a readable title and a single existing-format Pod4Me link. The link preserves URL/title/source feed information. Removed the standalone URL and instruction paragraph; parsing and confirmation are unchanged. Existing round-trip, reserved-character, personal-note and legacy-link tests cover the payload. No schema/backup change. The user reported copy/paste into Pod4Me because direct WhatsApp transfer was unavailable; the shorter format and download completion still need phone validation (check 40).

Canonical build passed in **1m 19s**, log `/tmp/pod4me-sharing-build.log` (reused). Release and device-test APKs compiled; **96 core checks, 11 schema/XML checks, 71 unit tests** passed with no failures/errors/skips. Lint: **0 errors / 24 warnings**. Shell syntax and diff whitespace checks passed.

Independently verified `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.9.apk`: version 1.0.9/code 17, non-debuggable, valid signature with the unchanged `134a…` certificate, 29,012,169 bytes each, identical SHA-256 `221c46348def8ea4cfb5282936d85803dbb166152a240ee160f55956413bd184`. No phone execution of the new format; only the user’s earlier outgoing-message and copy/paste report is recorded.


## HTTPS recommendations (2026-10-31, 1.0.10/code 18)

The user deployed the static website at `https://pod4me.aurelien-garivier.workers.dev/`. Outgoing messages now contain a readable title and one HTTPS link. Fragment data carries the recommendation type, source URL, title and optional feed, without sending that metadata to the static HTTP server. Android App Links uses the same hostname from `sharing.properties`; the browser page provides an explicit Open in Pod4Me intent link. Legacy custom links and raw feed/audio URLs remain supported. Incoming content still opens the existing confirmation; no automatic download or subscription. No schema/backup change.

Final canonical build passed in **1m 3s** using Android Studio JBR: **96 core checks, 11 schema/XML checks, 80 unit tests (zero failures/errors/skips)**, release assembly and device-test compilation. Lint: **0 errors / 24 existing warnings**. New regressions cover HTTPS output, metadata round-trip and link priority, foreign website fragments, missing payload, unsafe URLs/source feeds, duplicate fields, unknown types and legacy browser-fallback metadata. Website Node payload checks, JS syntax and `git diff --check` passed. The initial Gradle import issue and added manifest lint warning were fixed before final delivery.

Artifacts: `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.10.apk`, **29,012,349 bytes**, identical SHA-256 `7ce98239054868e65df0929738e83aaf4c736574465737e0e4e0c131648d5b8a`. Release script verified version/non-debuggable/signature and preserved the existing certificate. Packaging the website independently verified that the APK certificate matches the live association JSON. Log: `/tmp/pod4me-sharing-build.log`.

Server checks: landing HTML and `link.js` match the uploaded bundle; `/.well-known/assetlinks.json` returns HTTP 200 without redirects, application/json, package `com.pod4me`, certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`. No repeat website upload is needed. The ENS APK URL returned 404 and awaits user upload of the new APK. No phone tests or Android link-verification results are claimed; device check 41 is pending.


## Track and feed Info / Share redesign (2026-10-31, 1.0.11/code 19)

Track Info replaces the separate Heard/Unheard/4ever system-list rows with a Heard/Unheard switch and 4ever checkbox on one row. Marking Unheard retains the playback-service reset/stale-progress protection; other membership operations retain store behavior. Dates have Share on their right with Suggest and Copy URL. Copy info moves below the description; a top-right close icon replaces Done. Selectable lazy-loaded notes, scroll restoration, rating, custom lists, expiry, bookmarks and protected/current deletion remain.

Feed cards and feed settings use the same two-option Share menu: Suggest sends the existing typed HTTPS feed link; Copy URL copies the exact RSS URL. The recipient can Subscribe from the existing confirmation after opening Pod4Me; browser fallback may first require Open in Pod4Me. Feed settings uses the same feed-name/close header and URL/Share row, with No expiry beside default 4ever. Save commits edits; ×/Back/outside dismissal discard them. Default custom lists and validation remain. No schema, sharing protocol, backup or hosting change.

Final canonical build passed in **1m 27s** using Android Studio JBR: **96 core checks, 11 schema/XML checks, 80 unit tests**, zero failures/errors/skips. Release lint **0 errors / 24 existing warnings**. Release assembly and device-test compilation passed; `git diff --check` passed. Existing store/playback regressions cover heard thresholds, reset/stale progress and deletion protection; no new tests that merely mirror the UI layout were added. This does not verify actual device rendering.

Artifacts: `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.11.apk`, **29,045,117 bytes**, SHA-256 `c3fd538230cee20d655743e4aed3d90b5b524c462bc17264daad1376289bd299`. Release script checked version/non-debuggable/signature and certificate equality with the prior local release. Log: `/tmp/pod4me-info-build.log`. No phone tests were performed; device checks 43–44 are pending. ENS still serves the previously verified 1.0.10 until the user uploads this new APK; no website re-upload is required.
