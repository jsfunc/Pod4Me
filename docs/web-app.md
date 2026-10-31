# Pod4Me web

An independent browser app for phones and laptops, hosted alongside the existing Android sharing page. The web app lives at **https://pod4me.aurelien-garivier.workers.dev/app/** and is deployed. The root remains the recommendation landing page; Android verifies only that root path.

**The user deployed the package; public verification passed for the core app on 2026-10-31.** The live root, app HTML/JS and Android association match the prepared files. A real feed subscription and audio stream worked in Chromium through the public site. **Known deployment issue:** Apple rejected search (upstream HTTP 429) and charts (HTTP 403) from the Worker. Discovery is currently unavailable through this hosting path; RSS subscription/import and playback work. This requires a separate directory-access fix, not repeated deployment attempts.

## What is included

- RSS/Atom subscriptions, manual refresh, episode browsing, text search, heard/starred/new filters and date/stars/podcast/duration sorting. Newly discovered episodes have a lighter background; only a successful refresh clears the previous markers for that feed.
- Android-compatible subscription text files (`url [name]`), OPML import, subscription export, unsubscribe while retaining episode history, and editable podcast names.
- Streaming from publishers with a persistent player, ±10 seconds, seeking, previous/next, speed 1–3×, queue, named bookmarks and saved positions. Heard status is set at 95%; marking Unheard resets position.
- Stars, custom lists, heard/unheard, 4ever as a keeper marker, episode descriptions and Copy info.
- Share → Suggest or Copy URL on podcasts and episodes. Suggestions use the same HTTPS fragment format as Android. The root page offers Android and web choices; the web app asks before adding a shared episode or subscribing. No automatic playback or subscription on opening a link.
- Podcast search and suggestions using Apple's public directory. Every result has persistent thumbs feedback. Suggestions exclude subscriptions and the previous nonempty batch, demote recent results and use local keyword preferences. Older results can return; dislikes are not permanent bans. This is a simple heuristic, not semantic AI.
- Responsive layouts, a home-screen manifest/icons, light/dark appearance, keyboard-accessible controls and labelled dialogs.
- IndexedDB persistence and separate web JSON backups including library metadata, custom lists, bookmarks, positions, queue and suggestion feedback. Restore validates the file and requires confirmation before replacing this browser's library.

There are **no accounts or automatic synchronization**. Feed-list files and shared links connect Android and web; their full backup formats are intentionally separate. Web backups do not contain audio. Normal browser data clearing can erase the local library; export a backup when needed. A second tab warns and requires reload when the other tab saves to avoid overwriting its changes.

No offline audio downloads or automatic expiry are implemented. 4ever marks retained metadata; it does not download or protect an audio file. Playback position was small enough to include in this version. Background playback and lock-screen controls depend on the browser/device and remain subject to real iPhone testing. Some publishers' formats, HTTP media, redirects or access restrictions may prevent playback; the player shows an error.

On iPhone/iPad, open `/app/` in Safari, then **Share → Add to Home Screen**. This does not require the App Store or an Apple developer account. It still needs an internet connection for loading the app, refreshing feeds and streaming; there is no service-worker offline cache.

## Hosting without payment or card registration

The existing **Workers Free** account hosts the app files and a small metadata relay. No R2, KV, D1, paid plan, custom domain or account database is required. The APK stays on ENS because it exceeds Cloudflare's static asset file-size limit; the web package does not contain the APK or a signing key.

Build the deployment package from the repository:

```sh
python3 tools/package-web-app.py
```

Outputs:

- `dist/Pod4Me-web.zip`: complete Worker + static assets + deployment instructions.
- `dist/Pod4Me-web/README.txt`: exact deployment and rollback steps.
- `dist/Pod4Me-web/deploy.sh`: pinned Wrangler/Node wrapper for login and deployment.

The package verifies the existing signed release APK to generate `.well-known/assetlinks.json`, preserving the Android package and certificate. It does not rebuild or modify the APK. The root download link comes from `website/download-url.txt` and stays on ENS.

Read the generated README before deploying. Use the same Cloudflare account and **existing `pod4me` Worker**, preserving `pod4me.aurelien-garivier.workers.dev`. This replaces the static-only deployment with the same static pages plus the web app and metadata routes. A static ZIP drag-and-drop by itself is insufficient for the relay. Do not create a second hostname or activate a paid service. Keep the old package/deployment available for rollback.

The deployment helper explicitly sets `WRANGLER_CACHE_DIR` to its own `.wrangler/cache` directory. This fixes the reported permission error at the repository's `node_modules/.cache/wrangler`: that unrelated parent `node_modules` belongs to `nobody:nogroup` and is not writable by the user. The corrected helper passed `sh deploy.sh check` using cached deployment tools. No ownership changes or sudo are needed; this check does not deploy.

Free usage has quotas. Static assets and API requests have different accounting; feed/directory API calls use Worker invocation/CPU allowances. A small audience is the intended trial. Large feeds and heavy use need observation after deployment; local tests cannot establish Cloudflare CPU-quota compliance. See Cloudflare's [limits](https://developers.cloudflare.com/workers/platform/limits/) and [pricing](https://developers.cloudflare.com/workers/platform/pricing/).

## Why the relay exists

Browsers can read an external feed only if its publisher grants cross-origin permission (CORS). Many podcast feeds do not. The browser calls its own `/api/feed?url=…`; the Worker fetches the public feed and returns XML. Feed parsing happens in the browser. Audio and artwork load directly from publishers, not through the relay.

The Worker provides:

- `/api/feed?url=…`: public HTTP(S) feed, maximum 32 MiB, 30-second upstream deadline and at most five validated redirects; response retains the publisher's charset and exposes the final URL in `X-Feed-URL`.
- `/api/search?term=…&country=fr` and `/api/chart?country=fr`: bounded text-only directory results. Directory responses are limited to 2 MiB. Requests in a browser are spaced by at least 3.1 seconds; the chart-to-lookup chain also observes that delay.
- `env.ASSETS.fetch` for all other requests. Wrangler routes `/api` and `/api/*` to the Worker first; ordinary assets use static asset serving.

Successful public feed responses may be cached for five minutes and directory responses for ten. A repeated Refresh can therefore use recent cached metadata. Errors are not cached. There is no per-user stored data at the relay.

The relay rejects literal IPs, localhost/private-name patterns, credentials, nonstandard ports and redirects to disallowed destinations. It strips caller credentials/cookies, limits size/time/redirects, verifies XML preambles and rejects cross-origin browser calls. These are URL-level checks, **not DNS pinning or an authenticated abuse/rate-limiting service**; nonbrowser clients can still call public endpoints. Cloudflare networking controls and quotas still apply. No audio proxy, arbitrary upstream headers, XML entity expansion or wildcard CORS is provided.

Feed descriptions are rendered as inert text. XML DTDs are rejected. Backups are validated before replacement, including URLs, object structure, limits and references. Publishers receive normal media/artwork requests. Cloudflare sees feed/directory requests; Apple receives search terms/region, which can reflect liked podcast names. Individual thumbs, playback history and the library are not sent to Apple. Recommendation fragments are not sent in HTTP requests, although infrastructure can process normal request metadata.

## Development and checks

The application is buildless ES modules with no runtime npm dependencies. Node 18+ runs the local server and unit tests; the deployment helper supplies a temporary Node version supported by its pinned Wrangler.

```sh
node webapp/dev.mjs
# Open http://127.0.0.1:18876/app/ ; stop with Ctrl+C.
node webapp/tests/domain.test.mjs
node webapp/tests/storage.test.mjs
node webapp/tests/worker.test.mjs
```

Browser regression tests use Playwright as an optional developer-only dependency:

```sh
npm install --prefix /tmp/pod4me-web-qa playwright@1.56.1 --ignore-scripts --no-audit --no-fund
PLAYWRIGHT_PATH=/tmp/pod4me-web-qa/node_modules/playwright node webapp/tests/browser.cjs
```

Set `CHROMIUM_PATH` if Chromium is not at `/snap/bin/chromium`; set `POD4ME_TEST_URL` for another local port. Tests mock podcast feeds, the directory and a seekable WAV; they exercise real browser rendering, DOM XML parsing, IndexedDB, downloads and HTML audio. Screenshots go to ignored `dist/web-qa/`. Test fixtures are not shipped in the deployment package.

Source layout: `webapp/public/app/` holds the UI, domain logic and persistence; `webapp/worker.mjs` is the relay; `webapp/wrangler.jsonc` configures the existing Worker; `website/` holds the compatible root landing page. Keep root link syntax and asset association stable when changing either client.

Real iPhone/Safari, WhatsApp handoff and browser background playback still need acceptance testing. Public Worker feed access and brief publisher playback now pass; directory access fails as noted above. Do not infer those results from a successful local Chromium test or Wrangler dry run.

## Verification recorded on 2026-10-31

- **41 Node tests passed:** 7 domain, 7 storage (including atomic stale-tab rejection and old-record migration), 27 Worker transport/cache/security/directory cases. Existing root sharing payload/launch-link checks and JavaScript syntax checks passed.
- **Chromium 156 browser suite passed:** subscriptions, audio streaming/seeking, saved positions, queue and actual previous/next history, lists, stars, bookmarks, latest-refresh markers without lost user state, reload persistence, rotating suggestions and persistent feedback, Android text import/export, root Android/web handoff, web backup export/restore, and 4 RSS/Atom/OPML parser groups. Layouts checked at 1440, 390 and 320 pixels; screenshots inspected. Audio/feed/directory fixtures were used for this repeatable suite.
- **Real network smoke checks passed:** Radio France “Affaires étrangères” returned HTTP 200, 73,530 bytes and 22 episodes; the browser subscribed, parsed and persisted these episodes, then briefly played the actual publisher's audio (duration about 59 minutes). Apple science search returned 27 results and the French chart 37 through the local relay.
- **Deployment validation passed:** Wrangler 4.156.0 running on temporary Node 22.23.4 completed `deploy --dry-run` with the `ASSETS` binding. The final ZIP was checked against current app/Worker source, correct public Android fingerprint and ENS APK address. No key, APK or tests are included as deployed assets.
- **Not performed:** live Cloudflare deployment, iPhone/Safari/device acceptance, real WhatsApp handoff, background/lock-screen tests, or an Android rebuild. See device check 45. The existing Android APK remains version 1.0.11 locally; its last verified ENS upload remains 1.0.10.

For the optional real-network browser check (publisher availability can change):

```sh
PLAYWRIGHT_PATH=/tmp/pod4me-web-qa/node_modules/playwright node webapp/tests/live-smoke.cjs
```

## Public deployment verification, 2026-10-31 at 17:43 UTC

The user reported completing deployment. Public root, `/app/`, `/app/app.mjs` and `/.well-known/assetlinks.json` returned HTTP 200 and matched current packaged/source bytes. Association JSON has the existing signing certificate. The deployed feed relay returned the Radio France XML; a fresh Chromium browser context on the public origin subscribed to “Affaires étrangères”, displayed 22 episodes and played its actual audio briefly (59:12 duration). No user browser library was modified.

The deployed search API returned HTTP 502 with an upstream Apple HTTP 429 error. The deployed chart API returned HTTP 502 with an upstream Apple HTTP 403 error. Local relay successes do not establish Cloudflare upstream access; Discover/search is a known live limitation pending a fix. The checks were not retried in a loop.

The full ENS APK download returned HTTP 200, 29,045,117 bytes, and SHA-256 `c3fd538230cee20d655743e4aed3d90b5b524c462bc17264daad1376289bd299`, byte-identical to the signed local 1.0.11 APK. This supersedes the earlier 1.0.10 hosting snapshot. Actual iPhone, background playback, WhatsApp and Android installation checks remain pending. No source/runtime behaviour was changed during this public verification.
