# Hosting Pod4Me shared links

The user deployed the static bundle at **https://pod4me.aurelien-garivier.workers.dev/**. This Workers hostname is suitable for Android App Links; a separate Pages project is unnecessary. `sharing.properties` uses that hostname and `/` for the outgoing HTTPS base URL and Android manifest filter. Keep this hostname stable so existing shared messages continue to work.

On 2026-10-31 the root page returned HTTP 200 and matched the generated HTML. The deployed `link.js` matched the local source. The root `/.well-known/assetlinks.json` returned HTTP 200 directly, with `Content-Type: application/json`, and matched package `com.pod4me` and the existing `134a…` signing certificate. These are server checks, not evidence of Android verification or WhatsApp handoff on the phone.

## APK download on ENS — no R2

**Latest user decision:** no payment and no card number. The user accepts keeping the APK on ENS because the current file exceeds Cloudflare's normal static-file upload limit. The proposed R2 migration and ENS-folder retirement are cancelled; do not ask for an R2 URL or activate billing.

Upload `dist/Pod4Me.apk` to the ENS personal space as **https://perso.ens-lyon.fr/aurelien.garivier/diffusion/Pod4Me.apk**. The website already points there. Verified on 2026-10-31 at 14:46 UTC: HTTP 200, correct APK content type, 29,012,349 bytes; the downloaded file exactly matches local 1.0.10/code 18, SHA-256 `7ce98239054868e65df0929738e83aaf4c736574465737e0e4e0c131648d5b8a`, with valid unchanged `134a…` signature. This verifies the hosted 1.0.10, not the later 1.0.11 Info redesign: replace the ENS file with the new signed APK for that update. Phone download/install and WhatsApp handoff remain pending. Keep the folder and APK while this download URL is used. The app itself and the sharing page remain on the existing Cloudflare setup; no app rebuild or website re-upload is needed.

The APK is 29,012,349 bytes (about 27.7 MiB), above the **25 MiB per-file** Workers Static Assets limit. It cannot be uploaded unchanged through that static upload route. A future smaller APK could fit, but reducing/repackaging the app is not part of this hosting-only change. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/).

`website/download-url.txt` stores the ENS URL. The packager reads this file and supports an explicit `--download-url` override. Preserve the signing key and replace the ENS APK with the signed current release for future updates. No R2 bucket, checkout or subscription is needed.

## Website files and future updates

The site must remain public. `dist/Pod4Me-website.zip` includes `.well-known/assetlinks.json` at the root, alongside HTML, CSS, JavaScript, a 404 page and Cloudflare headers. Upload the contents without an additional parent folder. The deployed association file is already correct. The download button already targets ENS; the APK is now uploaded and verified.

After setting `website/download-url.txt` to the real public HTTPS APK URL, run `node tools/test_link_site.cjs`, `node --check website/page.js`, then `python3 tools/package-link-site.py` to recreate the bundle. The packager refuses to run with an unconfigured URL; its tracked configuration now specifies ENS. The packaging tool verifies the existing non-debuggable APK and derives its public signing fingerprint. It produces a ZIP, checksum, expanded preview and instructions under `dist/`. It needs the local Android SDK build tools, but no Cloudflare login or network. Optional `--apk` and `--download-url` override the source APK and download location.

The bootstrap ZIP was made with 1.0.9, whose signing certificate is preserved for 1.0.10. Its association JSON therefore remains valid. Android requires this JSON at the domain root, served publicly over HTTPS without redirects. No private key is uploaded. See [Android association requirements](https://developer.android.com/training/app-links/configure-assetlinks).

For a new deployment, Cloudflare Pages remains an alternative: Workers & Pages → Create application → Pages / Get started → Drag and drop your files → choose a project name → upload the ZIP → Deploy site. The user chose Workers and has already deployed successfully. If the hostname ever changes, update `sharing.properties`, rebuild the APK with the existing key, and retain the previous domain if old links must continue working. [Cloudflare Pages upload guide](https://developers.cloudflare.com/pages/get-started/direct-upload/).

## Receiving links

New messages contain one HTTPS link with recommendation data in the fragment. That fragment is not sent in HTTP requests to the website. Android can open verified links in Pod4Me; if a browser appears, the page offers an explicit **Open in Pod4Me** button using the existing custom scheme. Pod4Me still asks the recipient to Subscribe or Download. There is no automatic audio download, app analytics, database or messaging backend. Older custom-scheme messages retain their existing behavior.

Website payload checks and JS syntax checks passed, and the mobile-sized Chromium rendering was inspected. Actual WhatsApp clickability, Android verification, browser handoff and download/subscription behavior still need device check 41. Browser restrictions or app-link settings may cause the web page to appear first.
