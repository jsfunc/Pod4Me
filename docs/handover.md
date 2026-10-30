# Pod4Me development handover

Last reconciled: **2026-10-29**, after implementing ranked in-feed search for version 1.0.1/code 9, retaining this computer’s accepted signing key. This file is intended to let another session continue without the user remembering the conversation. New explicit user instructions supersede these recorded decisions.

## Start here

1. Read this file and [README](../README.md), then inspect `git status --short`, `git log -3 --oneline` and the relevant source.
2. Use [v1 verification](v1-verification.md) for audit disposition and release evidence, and [device checks](device-checks.md) for outstanding acceptance tests.
3. [goal.txt](../goal.txt) is the original request. The decisions below resolve later clarifications and intentional departures from it. [audit.txt](../audit.txt) is the earlier code audit; its findings have been addressed or explicitly documented, subject to pending device checks.

## Current state and remaining work

- Native Android app, application ID `com.pod4me`, version **1.0.1 / code 9**, SQLite schema **4**, portable JSON backup format **1**.
- Target phone: **Galaxy S24, One UI 9, Android 17**. Minimum Android API 34; compile/target API 36. UI is English and intentionally simple and functional.
- Working directory on the current machine: `/home/aurelien/repo/Pod4Me`. Branch `main`; origin `git@github.com:jsfunc/Pod4Me.git`.
- Current `HEAD` and locally recorded `origin/main` are `9bc33e0` (`Refine layouts and document development handover`); no fresh remote fetch was performed. The earlier artwork/navigation changes and handover are committed.
- Earlier working-tree changes add a confirmed Delete audio button beside Play/Queue/Details in Downloads, tighten those controls to 40 dp spacing, and use a dedicated 24 dp Feeds microphone vector without launcher padding. Both the bin and confirmation respect 4ever/current-playback protection; the store still enforces protection at deletion time. Library artwork and other screen layouts are preserved.
- New search implementation: Browse a feed, then open the magnifying glass. Search indexes every locally stored episode in that feed (downloaded or not, including retained history). It uses titles and visible HTML description text, case/accent folding, partial words and one-edit typos; highest relevance comes first, with publication descending then ID as ties. Every distinct query word must match; synonyms are deliberately omitted to stay offline and lightweight. Clear/close restores normal date order. The query is limited to 256 characters. No schema or backup-format change.
- `node_modules/`, `package.json` and `package-lock.json` (a local `@openai/codex` CLI install, not part of the app) sit in the working directory and are now listed in `.gitignore`: the `node_modules/` binaries exceed GitHub’s 100 MB limit and were once committed by mistake, which blocked `git push`. Never stage them.
- **Latest delivered search release:** `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.1.apk`, version 1.0.1/code 9, **28,879,355 bytes**, SHA-256 `a07f16b447ed1e826552644144ffd564e6d26a900f009421d4ca30d3e886b4e4`. Canonical build passed in 1m 29s: **96 core checks, 10 schema/XML checks, 45 unit tests, lint 0 errors / 22 warnings**, release assembly and device-test compilation. Independent signature, non-debuggable, version and checksum checks passed, with the same certificate as this computer’s previous APK. Phone search/update checks remain pending. Log copied to `/tmp/pod4me-feed-search-build.log`.
- Historical delivered artifacts (before these Downloads/Feeds changes; absent when this checkout was inspected): `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.0.apk`, **28,829,095 bytes**, SHA-256 `11b7c441144cb1c53a9998a5ef0f88ff549f352f17f52dc84409ccbf8a48951a`. They include the earlier artwork/navigation changes, not the new Downloads bin or Feeds icon correction. `dist/` is ignored by Git; a clone needs a rebuild and the existing signing key.
- Historical normal-terminal build succeeded in 40 seconds: **96 core checks**, **10 schema/XML checks**, **35 JUnit/Robolectric tests**, release lint **0 errors / 22 warnings**, release assembly and Android device-test APK compilation. CoreChecks is also run inside one JUnit test; these counts are not independent totals to add together.
- That historical APK was independently checked for version, non-debuggable status, signature, matching signing certificate, identical versioned copy and checksums. Details are in `v1-verification.md`.
- **No completed device acceptance run has been reported.** Real audio focus, Bluetooth/headset resumption, One UI button placement, DownloadManager behavior, upgrade preservation and the latest visual layout remain to be checked on the S24. The user saying “done” after a build request confirms running the build, not these device tests.
- Previous Downloads/Feeds source validation: canonical build using Android Studio JDK passed in 3m 38s: **96 core checks, 10 schema/XML checks, 35 unit tests, release lint 0 errors / 22 warnings, release assembly and device-test APK compilation**. No phone tests were run. Log: `/tmp/pod4me-download-controls-build.log`. The first build was set aside pending a signing decision. The user subsequently accepted a fresh installation using this computer’s key; the canonical rebuild passed in 9s (118 tasks up-to-date, one executed), and the regular fresh-install APK is delivered at `dist/Pod4Me.apk`. Its SHA-256 is `c9c8904d963c1dcf9fcbc350ea47ef390e2497d0f5af4ffdaaf53816b8c3e3bf` (28,846,059 bytes); signature verification passed with the newly accepted certificate. See `v1-verification.md` for validation and artifact evidence.
- **Accepted signing change:** the original build computer used certificate `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada`. It is unavailable here. After being told that changing keys requires uninstalling and loses local data/audio unless backed up, the user requested the new APK and said “i am uninstalling the older version”. Deliver the regular `com.pod4me` app using this machine’s persistent `~/.android/pod4me-v1.keystore`, certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`. Do not create a separate Test application. That first new-key APK required a fresh installation; version 1.0.1 reuses the new key and updates that installation normally. It still cannot update the old certificate. Retain and privately back up this key for subsequent updates and builds on other computers.
- Pending work: verify ranked feed search, the new Downloads/Feeds controls and the update on the S24, restore the user’s JSON library backup if one was made, complete remaining device acceptance, and save the working tree to Git. The user has not yet confirmed completed uninstall, backup, installation or restore. Do not invent a new feature backlog.

## Accepted product behavior

### Feeds, import, sharing and history

- Subscribe by RSS/Atom URL; feed names are customizable and refresh preserves custom names/settings.
- Refresh is manual, using a button. Downloads are manual and may use mobile data; roaming is disabled. No automatic refresh/download scheduler.
- Feed export is UTF-8 text, one **`url [podcast title]`** per line. Import reads only the URL before the first whitespace, ignoring all trailing text. Plain URLs, BOM, CRLF and Unicode whitespace are supported; encoded `%20` remains part of a URL. Limit: 1 MiB / 1,000 URLs per import.
- Unsubscribe is confirmed via the feed bin icon. It hides the subscription from Feeds, Latest, refresh and feed export while retaining downloads, pending downloads, queue, feed settings, ratings, bookmarks, lists and history. Resubscribing the same URL restores that feed's identity and data. An in-flight refresh must not resubscribe it.
- “Share” in episode details copies the **direct audio URL**. “Suggest” uses existing Android sharing apps; no accounts, user directory or server is required. Shared messages include the original URL and a `pod4me://feed` or `pod4me://episode` deep link. Incoming ACTION_SEND/deep links require confirmation before subscribing/downloading. Episode webpages are not resolved into audio URLs.
- Shared standalone audio belongs to pseudo-feed **ID `shared`, URL empty**. It is excluded from feed URL export. Restoring it onto an empty library must retain ID `shared`; later shared downloads otherwise fail foreign-key checks.
- In-feed search covers all locally stored metadata for the selected feed, regardless of audio availability. Refresh remains manual. Search ranks exact title matches above weaker partial/typo matches, ignores case/accents, requires each query word in either title or description, and allows arbitrary word order. Partial matching starts at three characters, typo matching at four (one insertion/deletion/replacement/adjacent swap). Synonyms are not included.
- Rolling feed history is deliberately retained. No automatic metadata pruning. Starred includes rated episodes whose audio is missing/expired so they can be downloaded again. Changing sort while in Starred stays in Starred.

### Lists, expiry and playback

- Permanent system lists: Unheard, Heard, 4ever. Custom lists can be created, renamed and deleted. Episodes can belong to several lists; each feed also has an automatic view and configurable default lists for future downloads.
- Default expiry is **30 days**, configurable per feed from 1–36,500 days or forever. Expiry deletion is checked on foreground launch, not by a timer.
- Heard begins at **95%**; seeking past the threshold counts. Its expiry becomes the earlier of its previous deadline and seven days from being marked heard. Repeated saves do not extend that deadline.
- Removing Unheard marks Heard. Marking Unheard resets position to zero, including seeking the current player to zero. Re-downloading an already-heard episode **preserves its history and Heard status**; this accepted decision supersedes the original blanket “new download is Unheard” wording.
- **4ever prevents both manual and automatic audio deletion.** Playing, buffering and paused current playback are protected until Stop. A cold restored session stays unloaded so merely opening the app does not protect the old episode from deletion.
- Removing audio keeps metadata, ratings, bookmarks, list memberships and position; it removes the queue entry. Unsubscribe is different and retains the queue/audio.
- Explicit queue entries play first; when exhausted, continue in the source-list sort order captured at Play. Missing audio is skipped. Restored queue entries without files remain available for manual download. Starting in the middle seeds up to five preceding downloaded items for Previous.
- Remember position per episode; remember speed **globally only**, range 1.0–3.0. Bookmark names are optional and editable. Background playback belongs to the service, with system progress, Play/Pause, Previous/Next and ±10-second commands.
- Sorts: publication date descending, download date descending, stars descending, feed name, progress ascending (0% first), with stable publication/id tie-breaks.
- Storage uses rounded integer decimal GB as requested; retain the exact header wording below rather than switching small values to MB.

### Current visual contract

- Header: one row **Pod4Me | `used: N - free: N GB` | Refresh**. No separate storage row.
- Feeds: **72 dp-wide artwork spanning full item content height** on the left. On the right, name plus Browse/Settings/Refresh icons; below, episode counts/expiry with smaller Share/bin icons. Errors also stay in the right content column.
- Latest: same full-height artwork on the left; feed/title, date/status/progress/expiry and action icons to its right. Download is an arrow; details is a question mark.
- Queue: full-height artwork on the left; title and up/down/bin controls to its right, feed name below. Missing-audio download control sits by that lower row.
- **Library keeps its compact layout:** 40 dp artwork beside feed/title; date/status/stars and percent/expiry underneath, with Delete/Play/Queue/Details icons to the right of those metadata lines, using 40 dp button spacing. The bin confirms audio deletion and is disabled for 4ever/current playback. Percent is `41%`, expiry is `Expires 28 Nov` without a year. No extra progress bar/action row. Do not propagate the Feeds/Latest artwork layout into Library without a new request.
- Player: artwork next to title; Previous, −10s, Play/Pause, +10s, Next stay on one row. Mini-player also has artwork.
- Bottom navigation: **icons only**, no visible Library/Latest/Feeds/Queue/Player labels. All buttons 64 dp high, all icons 24 dp. Feeds uses `ic_feeds.xml`, a navigation-sized microphone; `ic_podcast.xml` has launcher padding and must not be reused here. Accessible destination names remain.
- Artwork uses Coil caching and a placeholder if absent/unavailable. Larger artwork uses `ContentScale.Crop` with rounded corners. `ArtworkRow` measures right-hand content first and fits artwork in the same pass; avoid replacing this with intrinsic measurement of tooltip/subcomposed content.

## Implementation map and invariants

Source root: `app/src/main/java/com/pod4me/`.

| File / area | Responsibility and things to preserve |
| --- | --- |
| `PodApplication.kt` | Application-scoped store and feed client; shared error flow; cancellation propagates. |
| `MainActivity.kt` | `singleTask`, share/deep-link handling, notification permission, foreground lifecycle. 500 ms player UI ticker runs only while STARTED; pending download fallback reconciliation every 20 s. |
| `data/LibraryStore.kt` | Single mutex + SQLite transaction boundary, deletion protection, state publication, downloads, queue, user state. No destructive migrations. Notes loaded separately by `description(id)`. `feedSearchIndex(feedId)` reads 32-row batches under the mutex, then parses/indexes outside the lock on IO; no notes are added to snapshots. |
| `domain/Models.kt` | Lightweight state, lookup/count caches, sorting. Snapshot `Episode.description` is intentionally empty. `catalogueRevision` invalidates derived view order for structural/heard changes; progress-only updates reuse other episodes. |
| `data/LibraryBackup.kt` | Streaming metadata export and transaction-scoped merge restore. Runtime paths/download IDs never imported. Shared pseudo-feed ID is fixed. |
| `domain/FeedParser.java` | Bounded SAX parsing, namespace isolation, enclosure resolution, date/duration rules. Keep root RSS namespace support, extension precedence and DTD rejection. |
| `domain/FeedListFormat.java` | Character-based URL-prefix scanner; do not reintroduce Android-incompatible `(?U)` regex flags. |
| `domain/Policy.java`, `Upcoming.java` | Pure expiry/protection/heard/resume and queue/fallback rules, exercised by core checks. |
| `domain/FeedSearch.kt`, `ui/FeedSearchState.kt` | Temporary per-feed word index, cancellable scoring on Default, 250 ms query debounce. Compose keys isolate feed/revision/query results; progress-only ticks do not reload notes. Closing search or leaving the feed discards the index. |
| `domain/Suggestions.kt` | Bounded sharing/deep-link parsing and HTTP URL validation; confirm actions in UI. |
| `playback/PlaybackService.kt` | Owns ExoPlayer/MediaSession, source order, speed, resumption, callbacks. `PlayerClient.kt` is only the UI controller. |
| `ui/AppModel.kt` | UI operations, import/export/backup, storage updates on download/file-state changes; no storage scans on every playback tick. |
| `ui/PodApp.kt`, `FeedArtwork.kt` | Compose screens and artwork. Only player components consume changing position; cached list order avoids re-sorting the library every 500 ms. |

Important regression traps:

- Progress saves update one lightweight episode, without rereading all notes. No-op download reconciliation does not publish a new snapshot. Refresh avoids rewriting unchanged episode metadata.
- Marking Unheard increments a per-episode progress epoch. Player callbacks capture epochs when scheduling saves; stale pre-reset writes are ignored. Route current-item reset through the service. Same-item seeks save only the new position; measured player duration wins over feed duration. Playlist replacement discontinuities must not save an unrelated old position/duration.
- Keep the completed Android download ID in `manager_id` until audio deletion. Calling `DownloadManager.remove()` immediately on success would delete the downloaded file. `download_id` is for pending downloads; completion broadcast plus launch/fallback reconciliation handle state changes.
- Audio files live under the app's external Podcasts directory as `<episodeId>.audio`. Deletion checks canonical parent location and current/4ever protection. Missing files are reconciled while retaining history.
- Playback preferences are in `playback`: `current`, `order` (JSON array), `cursor`, `speed`. A cold session does not preload the saved item; media-button resumption loads it on request. External controllers cannot inject arbitrary media items.
- Schema assets use **one complete SQL statement per line**, not splitting on every semicolon. Migrations: 1→2 artwork URL; 2→3 subscribed flag; 3→4 completed manager ID. Increment schema and test upgrade paths for future changes.

Parser limits/choices: RSS 2-style documents (including coherent default/prefixed namespaces) and Atom; 64 MiB decoded HTTP response; 3-minute HTTP call timeout. Fixed RFC US timezone abbreviations; timezone-less/date-only dates use UTC. Unsafe HTTP credentials/schemes are rejected; illegal enclosure characters are percent-encoded while existing escapes/query semantics are preserved. Report skipped unsupported/invalid enclosures. Durations over seven days are unknown until measured. RSS 1.0/RDF, video enclosures and all DOCTYPE declarations remain unsupported.

## Backup contract

Feed-list export and full-library backup are separate actions. Manual JSON backup includes feed settings, episode metadata/notes, lists/defaults/memberships, ratings, progress, bookmarks, queue and playback preferences. It excludes audio, filesystem paths and DownloadManager IDs. Automatic cloud/device-transfer backup stays disabled.

Restore requires stopped playback and user confirmation. Merge by feed URL, episode GUID and list name. Backup settings/ratings/progress replace matching values; memberships are combined, bookmarks deduplicated, queue entries append without duplicates. Existing audio and pending downloads remain; missing audio never downloads automatically. Validation failures roll back the database transaction; preferences publish after commit. Current import limits: 256 MiB input, 500,000 records, 8,388,608 characters for a description, 65,536 for other row strings, 4,000,000 for playback order JSON. Preserve/validate references when changing this format.

## Build, signing and delivery

Canonical workflow is already scripted. No dependency on `/tmp` scripts or previous chat is required.

```bash
cd /home/aurelien/repo/Pod4Me
./tools/build-apk.sh > /tmp/pod4me-v1-build.log 2>&1
```

Read the log and actual output/report files before claiming success. The script runs Java core checks, Python schema checks, `testDebugUnitTest`, `lintRelease`, `assembleRelease` and `assembleDebugAndroidTest`. It then verifies version/non-debuggable/signing and compares any previous `dist/Pod4Me.apk` certificate before replacing deliverables and generating checksums. `/tmp` logs and scratch compiler scripts are disposable; durable results belong in `v1-verification.md`.

Environment: the system Java 17 at `/usr/lib/jvm/java-17-openjdk-amd64` currently lacks `javac`; the canonical build can use the complete installed JDK with `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh` (JDK 21, targeting Java 17). The previous machine had a full JDK 17. Use the shared SDK `~/Android/Sdk` (platform 36, build-tools 35.0.0, platform-tools), Gradle cache `~/.gradle`. `tools/setup-android.sh` sets up a new machine. Current pins include Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21 and Media3 1.8.0. Do not accidentally implement a newer Media3 callback signature without upgrading its dependency.

The sandbox can edit this repository and `/tmp`, but shared home/Gradle writes are restricted. In this session, an approved escalated run of the canonical script can access the signing directory and Gradle cache. A sandbox-only run failed copying the keystore; the first escalated run failed because system Java 17 lacks a compiler. Use the installed Android Studio JDK as above. If escalation is unavailable, use the normal-terminal fallback and inspect the resulting log/APK before claiming success.

Signing: `~/.android/pod4me-v1.keystore`, a persistent copy of this machine’s existing debug key. The release APK is **not debuggable**. The newly accepted certificate SHA-256 is `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`. The historical computer’s certificate was `6585371f3a4d176f2c50c706f3c0efa099aeca152ffcc3431876c698babe2ada`; switching to this machine’s certificate is the explicit fresh-install decision above, not a seamless update. Keep the new private key backed up outside Git and copy it privately when moving computers. Keystore paths are ignored; never commit credentials. Override variables and default alias/password handling are in `app/build.gradle.kts` and `tools/build-apk.sh`; do not ask the user to paste passwords into chat. Further certificate changes require another explicit migration decision.

For a future version bump, update **both** `app/build.gradle.kts` and the currently hard-coded version check/output filename in `tools/build-apk.sh`, then documentation. Use a higher versionCode for a new release. The previous UI iteration remained 1.0.0/code 8; ranked search bumps both version fields to 1.0.1/code 9 and the script’s versioned filename to `Pod4Me-1.0.1.apk`.

Phone download on the same Wi-Fi:

```bash
python3 tools/serve-apk.py
```

Open its printed address on the phone. For this accepted key change, install after removing the old-key installation; subsequent builds with the new key install over this new installation. Stop the server with Ctrl+C. The server serves only `dist/`. CI produces a debug APK artifact for build checking; its fresh-runner signing key is not the personal installed-app key. Use the local signed release for the user's normal update path.

## Checks and known limitations

Fast checks: `java tools/CoreTestLauncher.java` and `python3 -I tools/check_schema.py`. Full release checks: the build script. Connected Android tests: `./gradlew :app:connectedDebugAndroidTest` with the phone/emulator connected; these are not automatically executed by the build script.

Tests live under `app/src/test/java/com/pod4me/` and `app/src/androidTest/java/com/pod4me/`. Important regressions cover import whitespace/format, RSS/Atom namespaces and dates, >8 MiB feeds/64 MiB bounds/DTD rejection, migrations, stale progress, 10,001-episode lightweight state, backup remapping/rollback/shared downloads, Android manager statuses and cold/previous/reset player behavior.

Latest lint warnings are 11 tool/dependency-update notices, 7 KTX suggestions, 1 exported media-service notice, 1 redundant resource qualifier, and 2 `ModifierParameter` conventions introduced by the artwork helper. They are warnings, not errors; do not claim lint is warning-free. If adjusting the artwork API to satisfy conventions, preserve its default Library/player thumbnail sizes. Phone profiling and all recorded device acceptance checks remain pending.

## Saving the current work

The user explicitly wants enough written context to resume without relying on memory and has asked for commit/push commands. Since `.git` is read-only in the current agent sandbox, provide commands for their normal terminal when needed; never claim the changes were committed or pushed just because the files were saved. Inspect changes before staging, and omit unrelated `aurelien.txt`. No code build is required for documentation-only updates.
