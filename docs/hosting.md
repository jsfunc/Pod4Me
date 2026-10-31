# Hosting Pod4Me shared links

The user deployed the static bundle at **https://pod4me.aurelien-garivier.workers.dev/**. This Workers hostname is suitable for Android App Links; a separate Pages project is unnecessary. `sharing.properties` uses that hostname and `/` for the outgoing HTTPS base URL and Android manifest filter. Keep this hostname stable so existing shared messages continue to work.

On 2026-10-31 the root page returned HTTP 200 and matched the generated HTML. The deployed `link.js` matched the local source. The root `/.well-known/assetlinks.json` returned HTTP 200 directly, with `Content-Type: application/json`, and matched package `com.pod4me` and the existing `134a…` signing certificate. These are server checks, not evidence of Android verification or WhatsApp handoff on the phone.

## APK download on ENS — no R2

**Latest user decision:** no payment and no card number. The user accepts keeping the APK on ENS because the current file exceeds Cloudflare's normal static-file upload limit. The proposed R2 migration and ENS-folder retirement are cancelled; do not ask for an R2 URL or activate billing.

The APK is hosted at **https://perso.ens-lyon.fr/aurelien.garivier/diffusion/Pod4Me.apk**. Verified on 2026-10-31 at 17:43 UTC: HTTP 200, Android APK content type, 29,045,117 bytes; the full downloaded file exactly matches local signed **1.0.11/code 19**, SHA-256 `c3fd538230cee20d655743e4aed3d90b5b524c462bc17264daad1376289bd299`. This supersedes the earlier 14:46 UTC verification of 1.0.10. Phone installation and WhatsApp handoff remain pending. Keep the folder and APK while this download URL is used. The web app/sharing page are deployed on Cloudflare; the APK remains on ENS.

The APK is 29,045,117 bytes (about 27.7 MiB), above the **25 MiB per-file** Workers Static Assets limit. It cannot be uploaded unchanged through that static upload route. A future smaller APK could fit, but reducing/repackaging the app is not part of this hosting-only change. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/).

`website/download-url.txt` stores the ENS URL. The packager reads this file and supports an explicit `--download-url` override. Preserve the signing key and replace the ENS APK with the signed current release for future updates. No R2 bucket, checkout or subscription is needed.

## Website files and future updates

The independent web app is now deployed at `/app/`, with a metadata Worker at `/api/*`. **Public core app verification passed on 2026-10-31.** The Apple directory currently rejects requests from Cloudflare (429 for search, 403 for charts); feed subscriptions and streaming work. Use the new complete package and [web app instructions](web-app.md):

```sh
node tools/test_link_site.cjs
python3 tools/package-web-app.py
```

The resulting `dist/Pod4Me-web.zip` contains `public/` (root landing, association JSON and web app), `worker.mjs`, `wrangler.jsonc`, `deploy.sh` and `README.txt`. Follow that README to log in to the existing Cloudflare account and deploy the existing `pod4me` Worker. No paid resources or APK rebuild are required. The packaging tool derives the public signing fingerprint from the existing verified, non-debuggable APK; no private key is included.

Keep the same hostname. Root links keep their existing syntax; Android continues to verify the exact root path, while browsers use `/app/`. The root now offers **Open Android app** and **Open web player**, preserving the recommendation fragment for either destination.

`tools/package-link-site.py` and `dist/Pod4Me-website.zip` are the historical static-site workflow. Do not deploy that ZIP alone for the new version: the updated landing page points to `/app/`, which needs the complete app and running relay. Keep the previous live Cloudflare deployment available for rollback. The APK remains at the configured ENS URL.

Android requires `/.well-known/assetlinks.json` at the domain root, publicly over HTTPS without redirects. Preserve the existing package and certificate. [Android association requirements](https://developer.android.com/training/app-links/configure-assetlinks).

## Receiving links

New messages contain one HTTPS link with recommendation data in the fragment. That fragment is not sent in HTTP requests to the website. Android can open verified links in Pod4Me; if a browser appears, the deployed page offers explicit **Open Android app** and **Open web player** buttons using the existing custom scheme. Pod4Me still asks the recipient to Subscribe or Download. There is no automatic audio download, app analytics, database or messaging backend. Older custom-scheme messages retain their existing behavior.

Website payload checks and JS syntax checks passed, and the mobile-sized Chromium rendering was inspected. Actual WhatsApp clickability, Android verification, browser handoff and download/subscription behavior still need device check 41. Browser restrictions or app-link settings may cause the web page to appear first.
