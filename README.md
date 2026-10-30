# Pod4Me

A local Android podcast manager for a Galaxy S24 and newer phones. Native Kotlin / Jetpack Compose UI, SQLite storage, Android DownloadManager downloads, and Media3 background playback. No accounts, analytics, or application server.

For future development, start with [AGENTS.md](AGENTS.md) and the [development handover](docs/handover.md), which record the accepted requirements, current state, build/signing workflow and pending checks.

## Build status

The current source is **1.0.5 (version code 13)**. Successful refreshes finish without a confirmation popup; failed refreshes still report errors. Lists remember their scroll positions when navigating away and back, with separate positions for Library filters/sorts, feed Browse/search, Feeds, Queue, episode Info, feed settings, list management and the Player. The launcher background is darker orange (`#C43C1E`).

**New?**, after Export in Feeds, chooses up to ten podcast suggestions from Apple’s public search and country charts, excluding current subscriptions. Queue now has **Play queue** and a Play button on each downloaded item. Play can start queued audio when no track is loaded, with a clear error if the files need downloading. Duration labels, the orange app theme and the one-line star slider remain included. Release assembly and device-test compilation passed, along with 96 core checks, 10 schema/XML checks and 58 unit tests; lint has zero errors / 23 warnings. See [v1 verification](docs/v1-verification.md). Phone checks remain pending.

The user authorized a fresh installation for 1.0.2 because the signing key used for their installed 1.0.1 is unavailable. Version 1.0.5 retains the 1.0.2–1.0.4 key and can update that installation normally. Only the differently signed 1.0.1 installation needs the previously authorized uninstall/fresh install. Keep the existing personal signing key for future updates and copy it privately when moving computers. An update requires the APK certificate to match the installed app. See the [handover](docs/handover.md) for the actual local build certificate and signing history.

## Build an installable APK

On Ubuntu, from a normal terminal with internet access:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk curl unzip
cd /home/aurelien/repo/Pod4Me
./tools/setup-android.sh
./tools/build-apk.sh
```

Review and accept the Android SDK licenses when prompted. The setup script installs command-line tools, SDK platform 36, build tools 35.0.0, and platform-tools in `~/Android/Sdk`. Gradle and dependencies are cached in `~/.gradle`; both locations are shared with future projects. Set `ANDROID_HOME` or `GRADLE_USER_HOME` to override them. A full JDK 17 is required; a Java runtime without compiler tools is insufficient.

The build script runs core/schema checks, Android unit tests, release lint, release assembly and compilation of device tests. It produces a signed, non-debuggable **`dist/Pod4Me.apk`**, `dist/Pod4Me-1.0.5.apk`, and SHA-256 checksums. The APK supports Android 14+ (minimum API 34, compile/target API 36), including Android 17. Compatibility with the specific One UI 9 device still needs testing.

Transfer the APK to the phone and open it, allowing installation from the app used to open the file. For existing local installs, the script copies `~/.android/debug.keystore` once to the dedicated `~/.android/pod4me-v1.keystore`. Updates preserve data when this key matches the installed APK. On another computer, copy the existing personal keystore privately instead of adopting that computer’s debug key. The release APK is **not debuggable**, even though its certificate is retained from earlier builds. Keep a private backup of that keystore. The script also compares the certificate with an existing `dist/Pod4Me.apk` and stops on mismatch.

To use an existing personal keystore instead, set `POD4ME_KEYSTORE`, `POD4ME_STORE_PASSWORD`, `POD4ME_KEY_ALIAS` and `POD4ME_KEY_PASSWORD`. Its certificate must match the installed app to update it. Changing certificates is a separate migration; do not uninstall your current app to resolve a signing error.

To download directly on a phone connected to the same Wi-Fi, run `python3 tools/serve-apk.py` in your normal terminal and open the printed URL on the phone. The server exposes only `dist/`; stop it with Ctrl+C after downloading.

The GitHub Actions workflow `.github/workflows/android.yml` runs debug checks, release lint and compilation of device tests, and uploads a `Pod4Me-APK` artifact containing the installable debug APK. It does not publish a public release. GitHub artifacts are ZIP files: download and extract the APK before installing. Each fresh runner may use a different debug signing key. Use CI artifacts for build checking; the local release script with the existing personal key is the supported update path.

To expose Android tools to other projects, add this once to `~/.bashrc`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## Use

- **Feeds:** subscribe using an RSS/Atom URL; export UTF-8 text with one `url [podcast title]` per line. Import accepts both this format and plain URLs, ignoring everything after the URL on each line. Larger artwork spans the item height on the left. The title, Browse/Settings/Refresh controls, episode counts, expiry and smaller Suggest/Unsubscribe controls sit to its right. Long-press an icon for its label. Refresh is always manual. Successful completion only removes the progress bar; failures remain visible.
- **Latest:** browse compact episode rows by publication date or browse one feed. Larger artwork spans the item height; the feed name, title, date, played percentage, expiry and controls sit to its right. Use the download-arrow button to download and the question-mark button for details. Episode length appears beneath the buttons as `23min` (or `Length unknown` if unavailable). Downloads are manual and allow mobile data (roaming is disabled). Android handles downloads and their notifications even when the UI is closed.
- **Search inside a feed:** open a feed with Browse, then tap the magnifying glass beside its heading. Results update as you type (after a brief pause), highest relevance first. All query words must match somewhere in the title or description, in any order. Exact and title matches score higher; partial words of at least three characters and one-letter typos/transpositions in words of at least four characters are supported. Search ignores case/accents, works offline, and includes undownloaded episodes and retained feed history. Refresh manually to discover new feed entries. It does not query an external catalogue or expand synonyms. Clear or close search to return to newest-first order.
- **Library:** browse downloaded episodes by list or feed; sort by publication date, download date, stars, feed name, or played percentage. Compact cards put Delete, Details, Add to queue and Play icons beside date/progress/expiry, with tighter button spacing; deletion asks for confirmation and is disabled for 4ever and the current episode; progress and length appear as `41% · 23min`, with `4ever` retained for protected audio; expiry dates are available in Details. Starred also includes rated episodes whose audio has expired, so they can be downloaded again.
- **Details:** manage list memberships, rate episodes with a one-line 0–5 star slider (0 clears the rating), read/select/copy show notes, copy the direct audio URL, suggest an episode, visit bookmarks, or delete audio.
- **Queue:** tap **Play queue** to start the first entry with available audio, or Play beside a downloaded item to start that item. Missing audio offers Download. Use the up/down arrows to reorder and the bin to remove explicitly queued episodes; controls appear beside each title, with larger artwork spanning the full item height on the left. Removing a queue entry keeps its audio. When empty, playback follows the source list order captured when Play was pressed. Previous/Next and headphone controls use the actual player timeline. Starting in the middle includes up to five preceding downloaded episodes. Restored queue entries with missing audio offer a download button and are skipped until downloaded.
- **Player:** background playback, position scrubbing, a single row of playback controls, ±10 seconds, previous/next, global speed from 1.0× to 3.0×, and optionally named bookmarks. Playback positions persist periodically and on pause/seek/track changes.

Bottom navigation uses five equally sized icon-only buttons; their names remain available to accessibility services. Feeds uses a dedicated microphone icon without launcher padding so it appears comparable in size to the others. Library keeps its compact cards and 40 dp artwork.

Feed artwork appears next to feeds and episodes in Feeds, Library, Queue, Latest, and the player. RSS images, iTunes artwork, and Atom logos/icons are supported. Press **Refresh** after upgrading to retrieve artwork for existing feeds. Images are cached; feeds with missing or unavailable artwork display a podcast symbol. Database migrations preserve existing library data.

To unsubscribe, tap the **bin in the feed's row** and confirm. This removes the subscription from Feeds, Latest, refresh and export. Existing downloads, pending manual downloads, queued episodes, ratings, bookmarks, feed settings and list memberships remain. Re-adding or importing the URL restores the subscription using its existing data. The version 2-to-3 database migration preserves current subscriptions and episode data.

RSS/Atom responses are parsed incrementally, with a 64 MB limit on the uncompressed response and a three-minute request timeout. The parser retains episode metadata without buffering the whole response or constructing a DOM tree. Feeds larger than the former 8 MB limit are supported; feeds above 64 MB still report a size-limit error. XML DTDs (including harmless DOCTYPE declarations) and external entities are rejected. RSS 1.0/RDF and video enclosures are unsupported. Unsupported or invalid enclosures produce a skipped-episode count on the feed. RFC publication dates with US zone abbreviations are supported; dates without a timezone and date-only values use UTC. Declared durations longer than seven days are treated as unknown until measured by the player.

Episode metadata is retained even when an item disappears from a rolling feed. History is not automatically pruned. Show notes load only when Details is opened; periodic playback saves update one episode without reloading notes. Unchanged episode metadata is not rewritten on refresh.

Suggestions use Android's share menu. In WhatsApp, email, or a browser, share the message or URL into **Pod4Me**. Confirm a feed subscription or episode download in the app. Suggestions originating in Pod4Me contain the original URL plus a `pod4me://` link carrying the content type and source feed. The receiving app does not automatically subscribe or download. Raw episode *webpage* links are not resolved into media URLs; share the direct audio URL instead.

## Discover podcasts

In Feeds, tap **New?** immediately after Export. Pod4Me selects up to ten suggestions when requested: normally seven related-search results and three popular shows, filling remaining slots from either source. Search terms come from a rotating sample of subscribed feed names and repeated recent episode-title topics. Apple’s country chart uses the phone’s region (US fallback). This is keyword-based discovery, not an AI or listening-history recommendation service; language matching is not guaranteed. Each suggestion shows its publisher and why it was selected, plus **Subscribe** and an **Apple Podcasts** link. Subscribe is explicit and downloads no audio automatically.

Already subscribed feeds and duplicate feed URLs are excluded, and recently shown suggestions are ranked lower to provide variety. There may be fewer than ten when the directory has too few usable results or requests fail. Apple receives search terms and the region; the full library, ratings, notes and playback history are not uploaded. Calls use public endpoints without an account, API key or paid API plan. Only text metadata and links are displayed; Apple promotional artwork/audio is not copied. Requests are spaced at least 3.1 seconds apart and directory responses are cached in memory for ten minutes (up to 40 requests); selection is recomputed on every tap. There is no automatic discovery job. See [Apple’s Search API documentation](https://performance-partners.apple.com/search-api).

Scroll memory covers navigation, rotation and Android saved-state restoration. Re-selecting Latest returns to its current feed/search; use All feeds to return to the global list. Each Library filter/sort and feed search has its own position. Saved list anchors follow the same item when possible, with a nearby-position fallback when it disappears. Scroll memory and discovery caches are UI state, not part of library backups; a fresh launch without Android-restored state starts a new UI session.

## Portable library backup

In **Feeds**, use **Back up library** to save a versioned JSON file through Android's document picker. This is separate from **Export**, which still exports only `url [title]` feed lines. Library backups include subscriptions, custom names and expiry settings, episode metadata and notes, lists/memberships, ratings, progress, bookmarks, queue, global speed and saved playback context. Audio files, local paths and Android download IDs are excluded. Automatic cloud and device-transfer backup remain disabled.

Stop playback, choose **Restore library**, select the JSON file and confirm. Restore merges by feed URL, episode GUID and list name. Backup feed settings, ratings and progress replace matching values; memberships are combined, bookmarks are deduplicated, and restored queue entries append without duplicates. Existing audio and downloads remain. On another phone, missing audio must be downloaded manually. Queue entries remain available for downloading even if their feeds are unsubscribed. Invalid backups roll back the entire database change. Maximum backup input is 256 MB, with bounded record and field sizes.

## Storage rules

New downloads expire after 30 days unless their feed specifies a different period or infinity. Expiry is checked when the activity enters the foreground, not by a background timer. At 95% playback, an episode becomes Heard and its expiry becomes the earlier of its existing deadline and seven days from that moment. Seeking past the threshold counts as reaching it. Removing an episode from Unheard also marks it heard; marking it Unheard resets its saved position.

An episode in **4ever** cannot be deleted manually or by expiry until removed from that list. Playing, buffering and paused current playback are protected too; stop it before deleting. Opening a cold session does not load the previous episode or protect it from expiry; headset resumption loads it when requested. If no track is loaded, Play selects the first playable queued episode, then falls back to the saved episode. If neither is available, it explains how to download/select audio. Deleting audio retains ratings, bookmarks, list memberships, and playback history. Re-downloading an already-heard episode preserves its heard status and gets at most seven days unless protected by 4ever.

Unheard and Heard are derived views and are mutually exclusive. Each feed has an automatic view. Custom lists support create/rename/delete and many-to-many episode membership. Deleting a custom list does not delete audio. Default feed-list changes and expiry settings apply to future downloads.

## Verification

Without the Android SDK or network:

```bash
java tools/CoreTestLauncher.java
python3 tools/check_schema.py
```

With the Android SDK and dependencies:

```bash
./tools/build-apk.sh
```

See [device acceptance checks](docs/device-checks.md) before treating the app as ready for daily use.

With a connected Android device or emulator, `./gradlew :app:connectedDebugAndroidTest` runs feed-list import/export regression tests on Android's runtime. These complement desktop JVM tests, which do not reproduce all Android regex behavior.

## Code organization

- `domain/`: immutable UI models, sorting, expiry/protection rules, RSS/Atom parsing, suggestions, and queue ordering.
- `data/`: serialized SQLite transactions, feed fetching, durable OS downloads, cleanup, and completion reconciliation. Schema changes require an explicit migration; there is no destructive upgrade fallback.
- `playback/`: MediaSessionService owns ExoPlayer; the UI uses a MediaController. No activity owns the audio player.
- `ui/`: lifecycle-aware state and Compose screens.

Implementation references: [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [connecting a controller](https://developer.android.com/media/media3/session/connect-to-media-app), and [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
