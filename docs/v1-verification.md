# Pod4Me verification

Current source version: 1.0.20, code 28. Database version: 6. Android backup format: 2 (imports 1/2). Audit baseline: `audit.txt`. Earlier sections below preserve historical 1.0.0 evidence; later sections record the orange theme and duration-label releases.

## 2026-11-04 compact subscription toolbar — 1.0.20

Replaced the wrapping text-action toolbar with seven equal-width icon controls in one Row. Retained callbacks, busy-state rules, existing tooltips and content descriptions. Added envelope, disk/save and restore-history vectors; reused +, vertical arrows and circled question mark. Removed visible subscription media labels, keeping their icon accessibility descriptions and the existing light video tint. No extra card rows or changed Library layout. Canonical `./tools/build-apk.sh` passed in **2m 17s**: **112 core checks, 11 schema/XML checks, 116 unit tests**, zero failures/errors/skips; release lint **0 errors / 34 warnings**. Release and device-test APKs compiled. Signed non-debuggable **1.0.20/code 28**, `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.20.apk`: **29,412,641 bytes**, SHA-256 `23f6532278147b2e0d28c2588be5cbf2e44db2067655d6bb726df819e03a69a8`. Preserved `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f` certificate verified. Log `/tmp/pod4me-subscription-toolbar-build.log`. ADB lists no devices; phone layout/TalkBack check 55 remains pending. No public deployment.

## 2026-11-04 YouTube channel subscriptions and compact media distinction — 1.0.19

YouTube channel subscriptions are saved in the existing feed table without episode rows and opened through Android ACTION_VIEW; they are excluded from RSS refresh and Library filter chips. No schema or backup change. User chose external YouTube playback and separate audio/video subscriptions, then requested clear media types without reducing UI density. Small icons/labels stay in existing rows, and video cards receive a light tint. No change to Library cards.

Tests cover canonical handles/tab/tracking removal, channel URL validation and rejection of videos/playlists/lookalike hosts, import titles, duplicate subscription preservation, unsubscribe/seed behavior, and backup round trips without creating episodes. Actual FeedListFormat and YouTubeChannels classes parsed and normalized all 44 entries of `/tmp/videoPodasts.txt` with unique URLs and valid titles. All 44 official channel pages were fetched and identities checked; no phone UI/YouTube playback test has run. Final canonical `./tools/build-apk.sh` passed in **19s** (118/119 Gradle tasks up-to-date after the preceding 2m 9s verification build): **112 core checks, 11 schema/XML checks, 116 unit tests**, zero failures/errors/skips; release lint **0 errors / 34 warnings**. Release and device-test APKs compiled. Signed non-debuggable **1.0.19/code 27**, identical `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.19.apk`: **29,411,009 bytes**, SHA-256 `6e944e981fa7cbfaf16940c47ac7e0e06145d21120180f9ba0318ab9bf797ac4`. Preserved `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f` certificate. Log `/tmp/pod4me-channels-build.log`. No phone testing or public deployment; device checks 52–54 remain pending.

## 2026-11-04 video rotation and radio rewind — 1.0.17 / 1.0.18

User reported a video playback error on portrait→landscape rotation. The old lifecycle released the MediaController on ON_STOP, leaving the service's TextureView surface attached until Compose disposal (too late to send surface commands). `PlayerClient.disconnect` now clears the surface before release. A real MediaSession/MediaController regression fails before that change and passes afterward; it checks position/speed/play state, reattachment and late old-view cleanup. Exact physical-phone exception/codec behavior has not been captured. Canonical build passed in **1m 34s**: **112 core checks, 11 schema/XML checks, 106 unit tests**, release lint 0 errors, release assembly and device-test APK compilation. Signed `dist/Pod4Me-1.0.17.apk`: **29,361,365 bytes**, SHA-256 `b2aa36d1950f5cbb17c509158fbf80e1de241257a15224d3358d6e046feadb3d`, preserved certificate. No phone test; log `/tmp/pod4me-rotation-build.log`.

The user subsequently requested live-radio rewind and chose 30 minutes. The radio capture path converts MP3/ADTS frame boundaries into short compressed segments or mirrors HLS audio resources into a local sliding playlist. It does not transcode or open an HTTP listener. Retention is 30 minutes with a 128 MiB safety cap; resource files are deleted on eviction/stop/switch and abandoned cache directories are reclaimed at the next capture. Clear audio and AES-128 HLS are supported; DRM radio is rejected. Existing podcast/video sources still use the original default DataSource.

The Player tab exposes −10/+10 seconds, a seek slider for the available window, Pause/Resume and Back to live. Capture continues while paused; resume preserves the delayed position unless it expired, in which case Media3 recovers at the oldest available audio. Notification/headset ±10s are enabled, speed remains 1×, and podcast queue/history are untouched. The rewind window is not programme metadata or a broadcaster catch-up service. There is a few-second startup/live latency while segments accumulate.

Deterministic tests cover MP3/AAC frame preservation and measured duration, ICY metadata stripping, 30-minute eviction, disk limits, HLS initialization/encryption/discontinuities, cancellation, audio rendition/relative URL resolution, byte-range reads, and the real Media3 seekable radio timeline with pause/resume preserving its URI and position. Tiny test audio fixtures are generated silence, not broadcaster recordings. Real-stream probe captured France Info MP3, France Info ADTS AAC and BBC Radio 4 HLS through the actual RadioBuffer; desktop ffmpeg decoded three seconds from each generated local playlist without errors. Probe artifacts/log are under `/tmp/pod4me-rewind-probe` and `/tmp/pod4me-rewind-live-probe.log`; temporary probe test source was removed before release. This verifies capture/container handling, not physical-phone playback or long-duration/background behavior.

Canonical `./tools/build-apk.sh` passed in **1m 48s**: **112 core checks, 11 schema/XML checks, 113 unit tests**, zero failures/errors/skips; release lint **0 errors / 33 warnings**. Release and device-test APKs compiled. Signed non-debuggable **1.0.18/code 26**: identical `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.18.apk`, **29,394,133 bytes**, SHA-256 `34cb501f56f4a6eee8dfbe821225b9c4fc30d246a91d5b9e1b17850afe376320`. Preserved `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f` certificate verified independently. Log `/tmp/pod4me-rewind-build.log`. No phone test or public deployment. Physical-phone checks 52–53 remain pending.

## 2026-11-04 video podcasts — 1.0.16

Direct video RSS/Atom and Media RSS enclosures are classified and retained through refresh and backup. Explicit schema migration 5→6 adds `is_video`, default false, preserving existing downloads, position, stars, bookmarks and queue. Backup exports format 2 and imports formats 1/2. Existing format 1 records default to audio; refresh can update classification. Video downloads use the existing media file and expiry workflow. No dependency upgrade or new player service.

A TextureView in the existing Player tab attaches to the existing MediaController, tracks aspect ratio and detaches on disposal. Regular playback controls and saved position work for video. Play on an undownloaded video invokes app-only STREAM_VIDEO, which loads one URL without consuming the downloaded queue or replacing its resume context. Download remains separately available. Failure clears active playback and offers connection/download retry guidance. Cold launch does not start video streaming. Downloaded video follows the normal local queue. YouTube URLs, fullscreen and PiP are outside this change.

Three publisher feeds are seeded transactionally only if absent (existing unsubscribe state is preserved), with three recent metadata samples each and no automatic media/network downloads:

- [DW Video-Thema](https://rss.dw.com/xml/DKpodcast_videothema_de): short German learning/reportage videos.
- [Hands-On Tech video](https://feeds.twit.tv/hot_video_hd.xml).
- [This Week in Tech video](https://feeds.twit.tv/twit_video_hd.xml). TWiT explicitly publishes video RSS for podcast clients in its [subscription guide](https://twit.tv/subscribe).

All three latest enclosures returned range HTTP 206. Desktop ffprobe found H.264 video and AAC audio; ffmpeg decoded the first video frame of each. DW's complete 40,638,959-byte file returned HTTP 200. For Hands-On Tech (471,817,274 bytes) and This Week in Tech (3,010,290,787 bytes), inspected sparse local files containing only the first 8 MiB and last 4/16 MiB with their MP4 index. These are desktop transport/codec checks, not phone viewing. The legacy TED video feed returned audio enclosures and was excluded.

Regression tests cover RSS/Atom/Media RSS video, untyped MP4 URLs, audio enclosure precedence, upgrades from schema 1–5, one-time samples/unsubscribe, video flag/progress backup round trips and legacy imports, stream vs downloaded file URI, queue/resume/speed preservation, radio handoff and stream error cleanup. Initial migration test fixtures incorrectly included the new column in old schemas; fixtures now recreate old schemas correctly. Canonical `./tools/build-apk.sh` with Android Studio JBR passed in **1m 48s**: **112 core checks, 11 schema/XML checks, 105 unit tests**, zero failures/errors/skips; release lint **0 errors / 29 warnings**. Release assembly and device-test APK compilation passed. Delivered identical `dist/Pod4Me.apk` and `dist/Pod4Me-1.0.16.apk`: **29,344,981 bytes**, SHA-256 `e044e2ea87c8e80aaeff6af7337e7bed3cbdd3cc6d65b5a30dee2f081479fe25`. Version **1.0.16/code 24**, non-debuggable and unchanged `134a…` signing certificate independently verified. Log: `/tmp/pod4me-video-build.log`. No phone testing or public deployment. Phone check 52 is pending.

## 2026-11-04 radio ordering and Latest positioning — 1.0.15

Long-press and drag subscribed radio rows to reorder them, including frame-paced scrolling near list edges. Moves are previewed locally and persisted once on release; cancellation discards the preview. TalkBack offers Move up/down. The existing radio preferences array stores the order, and stale drag results retain new subscriptions without resurrecting removed ones. SQLite schema, podcast backups, signing and playback are unchanged.

Latest/all and each feed Browse initially select the newest publication <= current time and center its measured row within scroll boundaries. Future entries stay in descending publication order above that anchor. All-future lists start at the top; saved positions override defaults and refresh does not recenter. Search and Library retain their existing behavior.

Regression coverage adds durable order/reopen, new and removed subscriptions during stale drags, duplicate/unknown IDs, drag preview/cancellation/direction/drop/edge geometry, latest-date boundaries, all-future lists, and independent live/restored scroll positions. Canonical build passed in **20m 54s**: **108 core checks, 11 schema/XML checks, 101 unit tests**, zero failures/errors/skips; release lint **0 errors / 28 warnings**. Release and device-test APKs compiled. Signed non-debuggable `dist/Pod4Me-1.0.15.apk`: **29,342,141 bytes**, SHA-256 `e4f222f48c874d0109879077f02731663355dd9ffacf93502ec9a5b973b2becd`, unchanged signing certificate. ADB lists no devices; no phone test or deployment. Phone gesture/layout checks 50–51 remain pending.

## 2026-11-04 expanded radio catalogue — 1.0.14

The catalogue has **59 stations: 45 France, 5 UK, 5 US, 2 Spain, 2 Germany**. The original nine entries and their IDs/URLs are intact, default subscriptions remain France Info/France Culture/Europe 2, and existing on-device subscription preferences are not rewritten. Stations are grouped by country in the picker; search matches station names or country headings. Custom URL entry remains available. No database/backup format, playback engine or dependency changes in this catalogue update.

Added French choices: RTL, RTL2, Fun Radio, RMC, BFM Business, BFM Radio, NRJ, Nostalgie, Chérie FM, Rire et Chansons, Skyrock, RFM, Radio Classique, Sud Radio, Radio Nova, OÜI FM, TSF Jazz, Jazz Radio, M Radio, Radio FG, Latina, Ado, Beur FM, Radio Orient, RFI Monde, Chante France, Crooner Radio, Générations, AirZen Radio, KTO Radio, RCF, Africa Radio, Melody Radio, France Maghreb 2, Mon petit France Inter, Nostalgie 60/70. This broad national/network selection retains Tonic Lyon from the original request.

International choices: BBC World Service, BBC Radio 4, BBC Radio 2, LBC, Classic FM; NPR Program Stream, WNYC, WBUR, KCRW, KEXP; RNE Radio Nacional, Cadena SER; Deutschlandfunk, Deutschlandfunk Kultur.

Research used broadcaster pages, the [French metropolitan DAB+ list](https://www.dabplus.fr/metropolitain), [Radio Browser](https://www.radio-browser.info/) directory metadata, [BBC overseas listening guidance](https://help.bbc.com/hc/en-us/articles/42652680528659-Update-on-access-to-BBC-Sounds-outside-the-UK), [WNYC's published streams](https://prod.wnyc.org/audio-other-formats/), and [Deutschlandfunk's stream guidance](https://www.deutschlandfunk.de/livestream-100.html). The bundled app makes no Radio Browser request.

All **59** selected streams returned audio during bounded desktop HTTP checks (200/206); intentional time limits ended continuous MP3/AAC streams. ffprobe identified an MP3 or AAC audio track in every sample. For all **five HLS stations**, refreshed the playlist, downloaded a current segment and identified AAC audio: BBC Radio 2/4, KTO Radio, Mon petit France Inter and RNE Radio Nacional. The retired BBC manifesto links and obsolete WBUR/KCRW hostnames were replaced; certificate verification remained enabled. KTO uses an audio-only rendition. These checks establish transport/codec availability from this host, not listening quality or device acceptance. Candidate RNE Radio 3 was also checked but replaced by Cadena SER in the final two-station Spanish selection.

Canonical `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh` passed in **2m 2s**: **108 core checks, 11 schema/XML checks, 93 unit tests** (zero failures/errors/skips), release lint **0 errors / 28 warnings**, release assembly and device-test APK compilation. Delivered `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.14.apk`, **29,308,849 bytes**, SHA-256 `caba99e973913f22eb02f94cea37dc5dadbe7564ea81430acf196b8e62401e98`. Version **1.0.14/code 22**, non-debuggable status and unchanged `134a…` certificate verified; all 59 catalogue URLs are present in the delivered DEX. Log: `/tmp/pod4me-radio-catalogue-build.log`. No phone testing or deployment.

## 2026-11-04 live radio — local release 1.0.13

Android adds a sixth bottom destination with a broadcast antenna symbol. It lists subscribed radios with Play/Stop and confirmed unsubscribe, and + opens a searchable bundled French catalogue or custom station name/direct HTTP(S) MP3/AAC/HLS URL entry. Defaults are France Info, France Culture and Europe 2; additional catalogue entries are France Inter, France Musique, FIP, Mouv’, Europe 1 and Tonic Radio Lyon. The podcast Player’s “Preparing audio…” message is removed completely, including seek flashes.

The existing Media3 service owns both kinds of playback. Switching to live saves the podcast position, leaves the queue untouched, uses normal speed and omits episode history/expiry and seek controls. Switching back restores podcast speed and position. Radio system/headset resume reconnects to live; cold launch stays unloaded. Failure unloads with a station-specific retry message. HLS adds only the matching 1.8.0 Media3 module. The personal signing key, SQLite schema 5 and portable podcast backup format 1 are unchanged. Radio subscriptions are local preferences, excluded from podcast backup/feed export.

Regression tests cover default seeding and persistent empty lists, concurrent subscription saves and URL deduplication, invalid names/schemes/credentials/fragments, station identity with stream queries and HLS URLs, podcast/radio handoff with queue/position/speed, live command restrictions, Stop, failed/removed stations, and cold saved-radio sessions. The tests exposed a preference mutex held across the return to a paused UI dispatcher; the lock now covers the entire mutation on IO and is released before returning to the caller. All tests are rerun in the final canonical build.

Desktop stream checks: all nine catalogue URLs returned HTTP 200 `audio/mpeg` and nonempty data in six-second curl requests on 2026-11-04. Continuous-stream timeout was intentional. Tonic’s URL was read from [its official homepage](https://www.tonicradio.fr/); Radio France publishes its stream patterns in [the official API documentation](https://developers.radiofrance.fr/doc/tutorial-by-example/list-locals-and-webradios). These are transport checks, not listening or phone acceptance. Custom HLS is compiled but has not been played on a device.

Canonical `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh` passed in **1m 28s**: **108 core checks, 11 schema/XML checks, 93 unit tests** (zero failures/errors/skips), release lint **0 errors / 28 warnings**, release assembly and device-test APK compilation. Delivered `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.13.apk`, **29,308,849 bytes**, SHA-256 `01cf124542dc88a18f36cfa2b04f1b48454f4c2339d527fdc672cd692acb5b26`. Version **1.0.13/code 21**, non-debuggable status and unchanged `134a…` signing certificate verified. Log: `/tmp/pod4me-radio-build.log`. New lint warnings are KTX style suggestions for preferences edits. No phone tests or deployment.

ADB starts outside the sandbox but lists no devices. No connected instrumentation, phone installation, actual phone audio/background test or layout acceptance was performed. Device checks 47–48 remain pending. The web app, public APK hosting and Git remote have not been updated by this work.

## 2026-11-01 audit corrections — local release 1.0.12

The user approved the proposals derived from the new audit, explicitly excluded signing S1, and clarified that this is pre-production with no existing users. Audit text is retained as the original baseline; current disposition is below.

| Findings | Implemented change | Evidence / limitation |
| --- | --- | --- |
| B1 | Browser calls Apple directly; Worker directory routes removed; CSP/privacy updated; bounded sanitized requests, spacing, timeout and cache | Four directory unit tests and Chromium UI checks. Prepared assets on the real public HTTPS origin returned 25 search results, successful chart/lookup HTTP 200 and eight suggestions. This used local `/app/` asset interception, not deployment. |
| B2 | Retain 64 MiB feed limit; cap each captured title at 4,096 and notes at 1,048,576 UTF-16 characters, show shortening markers; reject oversized identifiers | Core tests include Unicode notes. Separate JVM `-Xmx256m` probes of 61 MiB feeds completed; see measurements below. Total retained metadata still scales with feed size; no claim of a fixed heap cap or ART profiling. |
| B3/B8 | Media RSS audio fallback and consistent generic/absent enclosure MIME handling | Core/browser XML tests verify fallback and enclosure precedence. Web public-host/standard-port restrictions retained and documented. |
| B4/B5 | Shared bounded XML preamble checking and BOM/charset/declaration decoding in relay/browser | UTF-16LE/BE, Latin-1 declarations, harmless CDATA/comment DOCTYPE text accepted; real DTDs rejected. |
| B6 | Refresh preserves subscription state; explicit Subscribe/Import remains intentional | Domain test plus browser unsubscribe → Refresh → explicit Subscribe flow. |
| B7 | Separate episode playback records and tiny shared revision key in IndexedDB v2 | Real IndexedDB migration, ordered snapshots, Unheard reset, stale full/progress writes, transaction rollback/recovery and backup/restore passed. 100,000 episodes: progress save 1.1 ms, 59-byte record plus revision; no full-library read/write. Metadata changes still perform full validation/saves. |
| B9 | Exact audio-URL reuse; unmatched shared downloads belong to Shared episodes even with Also subscribe | Store regression verifies no guessed catalogue identity or duplicate feed row. |
| B10/B11 | Busy actions explain retry; slashless root recommendation URLs parse normally | AppModel and sharing unit tests. Incoming suggestion remains available when busy. |
| B12/B13 | Reject clear HTML downloads; failed player stops/unloads before deletion protection clears; synchronous Unheard seek guard | DownloadManager/service tests verify retry, deletion after failure, paused protection and stale-progress reset. Unknown audio headers are not rejected; phone audio behavior remains pending. |
| B14 | English/French dates and two-digit years with explicit 1970–2069 window | Core regressions cover 02, 69, 70, 99 and French February. |
| S5 | Local APK server exposes only top-level APK/checksum files, with no listings or symlink escape | Eight HTTP file-boundary checks passed. |
| B15/D3 | Platform differences documented; handover current state separated from history | Documentation reconciled, historical evidence retained. |

S1 is excluded by the user. S2 transport-policy changes, S3 infrastructure rate limiting, S8 dependency upgrades and B16 scroll-cache eviction remain deferred as proposed. S4/S6/S7 need no current code change. For S3, a Worker rate limiter runs after invocation and is approximate, so it cannot guarantee protection of the daily request quota; no paid resources, forced browser-header authentication or new account flow was introduced. D1 remains open: ADB could not start due to host inotify/resource exhaustion; no instrumented Android tests or phone acceptance ran. D2's deployed-byte observation applies to the older public bundle.

Canonical build: `JAVA_HOME=/home/aurelien/android-studio/jbr ./tools/build-apk.sh`, log `/tmp/pod4me-audit-build.log`, **BUILD SUCCESSFUL in 2m 16s**. **108 core checks**, **11 schema/XML checks**, **85 unit tests** (zero failures/errors/skips); release lint **zero errors / 24 warnings**. Release APK and device-test APK assembled; device tests compiled, not executed. The core suite also runs inside the unit suite, so these counts must not be added as independent tests.

Delivered local files: `dist/Pod4Me.apk` and identical `dist/Pod4Me-1.0.12.apk`; version **1.0.12/code 20**, **29,045,117 bytes**, SHA-256 `5a9afba96ee256dbc5b1ea0f8827e1652b22b3294f0a57a8a5cfcd51b21cdfed`. The release script verified the version, non-debuggable status and unchanged certificate `134a95237bf3ac990be2f7d1f33fdc7e9e6d16682401d3445cd9ad6fc10ff03f`. Signing code and credentials were not changed.

Desktop parser probes streamed approximately 61 MiB through the compiled parser with `-Xmx256m`, 8,192-character descriptions and no retained raw XML buffer:

| Notes | Episodes | Live heap after GC | Sum of heap-pool peaks | Elapsed including GC wait |
| --- | ---: | ---: | ---: | ---: |
| Latin `x` | 7,690 | 67.4 MiB | 169.9 MiB | 454 ms |
| Greek `Ω` | 3,875 | 66.7 MiB | 223.2 MiB | 438 ms |
| CJK `界` | 2,590 | 45.8 MiB | 188.3 MiB | 363 ms |

Pool peaks need not occur simultaneously. These measurements establish completion for these desktop fixtures, not an Android heap guarantee. Per-field caps prevent a single enormous retained note/title; incremental database ingestion remains unnecessary for the measured cases and is not included.

Web validation: 42 Node tests, full Chromium suite (six XML parser groups plus real IndexedDB stress/rollback cases), prepared-origin live feed/audio/Apple checks and Wrangler dry run passed. `dist/Pod4Me-web.zip` is prepared. No Worker deployment, ENS upload, Android installation, iPhone/WhatsApp or physical media-control test was performed.

Follow-up publication/acceptance: ENS was verified to host the exact signed 1.0.12 APK on 2026-11-01 at 12:45 UTC. After deployment instructions, the user reported “tests are ok” and requested commit/push. The live HTML and five app JavaScript modules were then downloaded and confirmed byte-identical to current source. This supersedes the earlier publication-pending status; individual device results and instrumented Android execution are not inferred from the general report.

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
