# Device acceptance checks

These checks are pending. Run them on the Galaxy S24 / One UI 9 after a successful build. Android controls and background behavior cannot be verified solely by source inspection or JVM tests.

1. Install `dist/Pod4Me.apk`, open it, and verify content respects display cutouts, status/navigation bars, dark mode, larger text, and rotation.
2. Subscribe to two feeds. Import duplicates alongside an invalid URL; valid subscriptions should survive the reported error. Rename a feed, refresh, and confirm the custom name survives. Export and inspect the `url [podcast title]` lines, then import a mixture of labeled lines and plain URLs; only the URLs should be read.
3. Download on Wi-Fi and mobile data, background the app, and confirm completion is visible on returning. Cancel and retry a download. Test lost connectivity, an HTTP error, and insufficient storage.
4. Add a custom list, rename it, assign it as a feed default, and download another episode. Check Unheard, feed view, and the default list. Delete the custom list and confirm the audio remains.
5. Rate multiple episodes and check all five sorts, including 0% first and rated-only descending stars. Check a rated expired episode can be found in Starred and downloaded again.
6. Play an episode and put the app in the background, lock the phone, and use another app. Verify audio focus during a call and pause when headphones disconnect. Check the Android media panel's progress, play/pause, previous/next, and ±10-second buttons. Available button placement is controlled by One UI.
7. Change speed, pause, close/reopen, and resume. Verify global speed and episode position. Add named and unnamed bookmarks, rename and delete them, and seek to bookmarks from Player and Details.
8. Seek to 94%, then 95%, and verify the Unheard-to-Heard transition occurs only at the threshold. Verify removing from Unheard marks heard. Repeated progress updates must not extend the seven-day deadline.
9. Add episodes to a queue in a different order from the library, reorder/remove queue entries while playing, and verify manual Next, automatic completion, and source-list fallback. Explicitly queue a previously played episode. Stop at the end of the list, then verify Play restarts the final episode.
10. Protect a download with 4ever; verify Delete is disabled and launch cleanup preserves it. Remove protection and verify deletion keeps stars/bookmarks/history. Stop the current episode before deleting it.
11. For expiry tests, use a disposable emulator or test database with a past expiry. Launch with ordinary, protected, and currently playing expired downloads; only the ordinary inactive download should be removed. Do not change your everyday phone's clock for testing.
12. Send an episode suggestion via WhatsApp/email, then share that text into Pod4Me. Verify its title, audio URL, and optional feed subscription. Repeat for a feed and a plain audio URL. Confirm cancellation causes no download/subscription and export contains no shared-audio pseudo-feed.
13. Force-stop while downloading and relaunch after completion. Verify the completed file is reconciled once. Reopen after process death and confirm queue, lists, ratings, bookmarks, and playback positions are retained.
14. Upgrade an existing 0.1.0 installation to 0.2.0 using the same signing key. Verify subscriptions, downloaded files, ratings, bookmarks, and lists survive. Refresh feeds and check artwork in Feeds, Library, Queue, Latest, and next to the player's title. Test feeds without images, an unavailable image URL, and previously cached images while offline.
15. Check Previous, ±10 seconds, Play/Pause, and Next stay in a single row on the S24. Verify compact feed episode rows show a download arrow and a question-mark details action, and that screen readers announce each icon's purpose.

Test results, device details, and any failures should be recorded here after execution.
