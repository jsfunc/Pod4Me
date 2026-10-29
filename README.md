# Pod4Me

A local Android podcast manager for a Galaxy S24 and newer phones. Native Kotlin / Jetpack Compose UI, SQLite storage, Android DownloadManager downloads, and Media3 background playback. No accounts, analytics, or application server.

## Build status

The latest source is 0.2.1. It includes compact icon controls, titled feed exports, and feed artwork, plus a fix for an Android-specific import/export regex error. Its 65 dependency-free core checks and eight standalone SQLite/XML checks pass. The updated Android build and on-device checks are pending; earlier builds passed compilation, unit tests, and lint.

## Build an installable APK

On Ubuntu, from a normal terminal with internet access:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk curl unzip
cd /home/aurelien/repo/pod4me
./tools/setup-android.sh
./tools/build-apk.sh
```

Review and accept the Android SDK licenses when prompted. The setup script installs command-line tools, SDK platform 36, build tools 35.0.0, and platform-tools in `~/Android/Sdk`. Gradle and dependencies are cached in `~/.gradle`; both locations are shared with future projects. Set `ANDROID_HOME` or `GRADLE_USER_HOME` to override them. A full JDK 17 is required; a Java runtime without compiler tools is insufficient.

The build script runs unit tests and lint before producing **`dist/Pod4Me.apk`** and its SHA-256 checksum. The APK supports Android 14+ (minimum API 34, compile/target API 36), including Android 17. Compatibility with the specific One UI 9 device still needs testing.

Transfer the APK to the phone and open it, allowing installation from the app used to open the file. The local debug signing key normally resides at `~/.android/debug.keystore`; keep that key to install subsequent local builds as updates without uninstalling and losing app data.

To download directly on a phone connected to the same Wi-Fi, run `python3 tools/serve-apk.py` in your normal terminal and open the printed URL on the phone. The server exposes only `dist/`; stop it with Ctrl+C after downloading.

The GitHub Actions workflow `.github/workflows/android.yml` runs the same checks and uploads a `Pod4Me-APK` artifact containing the installable debug APK. It does not publish a public release. GitHub artifacts are ZIP files: download and extract the APK before installing. Each fresh runner may use a different debug signing key; use local builds with the same key for persistent personal installs until dedicated release signing is configured.

To expose Android tools to other projects, add this once to `~/.bashrc`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## Use

- **Feeds:** subscribe using an RSS/Atom URL; export UTF-8 text with one `url [podcast title]` per line. Import accepts both this format and plain URLs, ignoring everything after the URL on each line. Edit feed names, expiry periods, and default lists. Refresh is always manual.
- **Latest:** browse compact episode rows by publication date or browse one feed. Use the download-arrow button to download and the question-mark button for details. Downloads are manual and allow mobile data (roaming is disabled). Android handles downloads and their notifications even when the UI is closed.
- **Library:** browse downloaded episodes by list or feed; sort by publication date, download date, stars, feed name, or played percentage. Starred also includes rated episodes whose audio has expired, so they can be downloaded again.
- **Details:** manage list memberships, rate episodes, read/select/copy show notes, copy the direct audio URL, suggest an episode, visit bookmarks, or delete audio.
- **Queue:** reorder and remove explicitly queued episodes. When empty, playback follows the source list order captured when Play was pressed. Previous/Next and headphone controls use the actual player timeline.
- **Player:** background playback, position scrubbing, a single row of playback controls, ±10 seconds, previous/next, global speed from 1.0× to 3.0×, and optionally named bookmarks. Playback positions persist periodically and on pause/seek/track changes.

Feed artwork appears next to feeds and episodes in Feeds, Library, Queue, Latest, and the player. RSS images, iTunes artwork, and Atom logos/icons are supported. Press **Refresh** after upgrading to retrieve artwork for existing feeds. Images are cached; feeds with missing or unavailable artwork display a podcast symbol. The version 1-to-2 database migration adds artwork metadata without removing library data.

Suggestions use Android's share menu. In WhatsApp, email, or a browser, share the message or URL into **Pod4Me**. Confirm a feed subscription or episode download in the app. Suggestions originating in Pod4Me contain the original URL plus a `pod4me://` link carrying the content type and source feed. The receiving app does not automatically subscribe or download. Raw episode *webpage* links are not resolved into media URLs; share the direct audio URL instead.

## Storage rules

New downloads expire after 30 days unless their feed specifies a different period or infinity. Expiry is checked when the activity enters the foreground, not by a background timer. At 95% playback, an episode becomes Heard and its expiry becomes the earlier of its existing deadline and seven days from that moment. Seeking past the threshold counts as reaching it. Removing an episode from Unheard also marks it heard; marking it Unheard resets its saved position.

An episode in **4ever** cannot be deleted manually or by expiry until removed from that list. Active/paused current playback is protected too; stop it before deleting. Deleting audio retains ratings, bookmarks, list memberships, and playback history. Re-downloading an already-heard episode preserves its heard status and gets at most seven days unless protected by 4ever.

Unheard and Heard are derived views and are mutually exclusive. Each feed has an automatic view. Custom lists support create/rename/delete and many-to-many episode membership. Deleting a custom list does not delete audio. Default feed-list changes and expiry settings apply to future downloads.

## Verification

Without the Android SDK or network:

```bash
java tools/CoreTestLauncher.java
python3 tools/check_schema.py
```

With the Android SDK and dependencies:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See [device acceptance checks](docs/device-checks.md) before treating the app as ready for daily use.

With a connected Android device or emulator, `./gradlew :app:connectedDebugAndroidTest` runs feed-list import/export regression tests on Android's runtime. These complement desktop JVM tests, which do not reproduce all Android regex behavior.

## Code organization

- `domain/`: immutable UI models, sorting, expiry/protection rules, RSS/Atom parsing, suggestions, and queue ordering.
- `data/`: serialized SQLite transactions, feed fetching, durable OS downloads, cleanup, and completion reconciliation. Schema changes require an explicit migration; there is no destructive upgrade fallback.
- `playback/`: MediaSessionService owns ExoPlayer; the UI uses a MediaController. No activity owns the audio player.
- `ui/`: lifecycle-aware state and Compose screens.

Implementation references: [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [connecting a controller](https://developer.android.com/media/media3/session/connect-to-media-app), and [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
