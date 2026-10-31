#!/usr/bin/env python3
"""Prepare the complete Pod4Me Cloudflare Worker and static assets; never deploy."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WRANGLER_VERSION = "4.156.0"

DEPLOY = f'''#!/bin/sh
set -eu
cd "$(dirname "$0")"
if ! command -v npx >/dev/null 2>&1; then
  printf '%s\\n' 'npm/npx is needed. See README.txt in this folder.' >&2
  exit 1
fi
export WRANGLER_SEND_METRICS=false
# Wrangler otherwise searches parent folders for node_modules/.cache, which
# may belong to a different user. Keep deployment cache beside this script.
export WRANGLER_CACHE_DIR="$PWD/.wrangler/cache"
mkdir -p "$WRANGLER_CACHE_DIR"
case "${{1:-deploy}}" in
  login) set -- login ;;
  check) set -- deploy --dry-run --outdir .dry-run ;;
  deploy) set -- deploy ;;
  *) printf '%s\\n' 'Usage: sh deploy.sh [login|check|deploy]' >&2; exit 1 ;;
esac
# The supplied Node 22 runtime is used by Wrangler even on a Node 18 computer.
# npm may print EBADENGINE while preparing its cache under the older host Node;
# Wrangler itself runs with the supported downloaded Node runtime.
exec npx --yes --package=node@22 --package=wrangler@{WRANGLER_VERSION} wrangler "$@"
'''


def instructions(download_url):
    return f'''POD4ME WEB — INSTALL ON YOUR EXISTING CLOUDFLARE WORKER

This package contains the sharing page, Android association, web app, and
podcast metadata relay. No APK rebuild, paid plan, card, R2, database, or
Cloudflare storage setup is required. Keep using the Workers Free plan.

The new web app will be:
  https://pod4me.aurelien-garivier.workers.dev/app/
Your existing shared links keep using:
  https://pod4me.aurelien-garivier.workers.dev/
The Android APK stays at:
  {download_url}
Keep the APK on ENS while that download URL is in use.

DEPLOY FROM THIS UBUNTU COMPUTER

1. Open a terminal. The prepared folder is already available, so run:
     cd /home/aurelien/repo/Pod4Me/dist/Pod4Me-web

   If you downloaded Pod4Me-web.zip elsewhere, extract the ZIP into a new
   folder first, then open a terminal in the extracted folder containing
   deploy.sh, worker.mjs, wrangler.jsonc, and public/.

2. Sign in to Cloudflare:
     sh deploy.sh login

   The first run downloads the deployment tools. A browser opens. Sign in
   to the SAME Cloudflare account that owns the existing pod4me Worker and
   approve Wrangler's access. If the browser does not open, copy the login
   URL printed in the terminal into your browser. Return to the terminal
   after the login succeeds. Do not create another Worker or a Pages site.

3. Optional local package check (does not upload or deploy):
     sh deploy.sh check

4. Upload the complete new version:
     sh deploy.sh deploy

   If asked to select an account, select the one with your existing pod4me
   Worker. If Wrangler warns that this replaces dashboard-edited code or
   settings, this package intentionally replaces that Worker with the
   complete web app and the preserved sharing/Android association files.
   Keep the name pod4me. Do not enable a paid plan or enter a card.

   Wait for the deployment URL. It should be exactly:
     https://pod4me.aurelien-garivier.workers.dev

   The ZIP is a source/deployment package, NOT a static dashboard-upload
   ZIP. Uploading it as static files would omit the running feed relay.
   Do not deploy the old Pod4Me-website.zip by itself: the current sharing
   page now links to /app/, which needs this complete deployment.

5. Open the web app and try subscribing to a podcast, listing episodes,
   playing one, and importing/exporting a feed list. Test an Android link
   and a web link from WhatsApp. Existing Android links are preserved.
   Open /.well-known/assetlinks.json to check that it returns JSON.

IPHONE AND LAPTOP USERS

Send users this address:
  https://pod4me.aurelien-garivier.workers.dev/app/

On iPhone, open it in Safari, open Share, then Add to Home Screen and Add.
Some iOS versions put Add to Home Screen under the Share sheet's additional
actions. The resulting icon opens the same independent web app. It can also
be used directly in Safari or a laptop browser; there is no App Store step.

Subscriptions and the library live in that browser/device. There is no
account or synchronization. Exchange feed-list files and Pod4Me links with
Android. Clearing site data can remove the local library, so export/back up
anything you want to keep. Audio streams from publishers; offline downloads
and expiry rules are deferred. Safari/background playback behavior still
needs a real iPhone test.

NO PAYMENT OR EXTRA HOSTING

Static files use Workers Static Assets. /api/* requests use the Free
Worker's request and CPU allowances. When a free limit is reached, requests
can fail until the limit resets; this configuration does not enroll you in
paid services. Never select an upgrade or billing setup for this package.
The relay holds no accounts or personal libraries, proxies only bounded
RSS/Atom and Apple podcast metadata, and caches successful metadata for
5–10 minutes. Audio/artwork remain on their publishers' servers.

IF A COMMAND FAILS

- npm/npx is installed on the development computer. On another computer,
  install the current Node.js LTS from https://nodejs.org/ first.
- The script downloads Node 22 and pinned Wrangler {WRANGLER_VERSION} into
  npm's cache, without changing your system Node. On a Node 18 host, npm
  can show EBADENGINE during initial setup; Wrangler then uses Node 22.
- If login fails, run 'sh deploy.sh login' again. If multiple accounts are
  offered, check the Cloudflare dashboard for the one holding pod4me.
- The helper keeps Wrangler's cache in this folder's .wrangler/cache,
  avoiding permission errors from an unrelated parent node_modules folder.
  Run these commands as your normal user; sudo is not required.
- If a new URL/account appears, do not distribute that address: check the
  selected Cloudflare account. Existing recommendations use the old host.
- If Cloudflare asks for billing, stop that setup step; no paid resources
  are needed. Keep the Android APK on ENS.
- Publisher errors can be temporary. The web relay accepts public HTTP(S)
  RSS/Atom feeds up to 32 MiB and waits up to 30 seconds. Some publishers
  block cloud servers. HTTP-only audio may fail in secure browsers.

DEVELOPER NOTES

Rebuild this package after source changes:
  python3 tools/package-web-app.py
Local source preview (Node 18+, no dependencies; no deployment):
  node webapp/dev.mjs
  http://127.0.0.1:18876/app/

The public Android association is generated by tools/package-link-site.py
from the verified existing release APK certificate. The APK, private key,
credentials, tests, and source outside public/ are not uploaded as assets.
worker.mjs is Worker code, not a public asset. wrangler.jsonc intentionally
keeps the existing Worker name and /api routing. Public feed URL validation
rejects local names/IP literals/credentials/nonstandard ports and validates
redirects; it does not pin publisher DNS. It is not an authenticated API.

Official references:
https://developers.cloudflare.com/workers/wrangler/install-and-update/
https://developers.cloudflare.com/workers/static-assets/binding/
https://developers.cloudflare.com/workers/static-assets/routing/worker-script/
https://developers.cloudflare.com/workers/platform/pricing/
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "dist/Pod4Me.apk")
    parser.add_argument("--download-url", help="Override the configured public HTTPS APK URL")
    args = parser.parse_args()
    app = ROOT / "webapp/public/app"
    required = ("index.html", "app.mjs", "app.css", "domain.mjs", "storage.mjs", "manifest.webmanifest", "icon.svg", "icon-192.png", "icon-512.png")
    missing = [name for name in required if not (app / name).is_file()]
    if missing:
        parser.error("Web app files are missing; finish the app before packaging: " + ", ".join(missing))
    if any(path.is_symlink() for path in app.rglob("*")):
        parser.error("Web assets must be regular files, not symlinks")
    for path in app.rglob("*"):
        if path.is_file() and path.stat().st_size > 25 * 1024 * 1024:
            parser.error(f"Asset exceeds Cloudflare's 25 MiB static-file limit: {path}")

    # Reuse the release packager's verified signing/public-download logic.
    # Never copy a fingerprint from a dated handover or ship an APK as an asset.
    command = [sys.executable, str(ROOT / "tools/package-link-site.py"), "--apk", str(args.apk)]
    if args.download_url:
        command += ["--download-url", args.download_url]
    environment = os.environ.copy()
    jbr = Path.home() / "android-studio/jbr"
    if not environment.get("JAVA_HOME") and (jbr / "bin/java").is_file():
        environment["JAVA_HOME"] = str(jbr)
    subprocess.run(command, cwd=ROOT, env=environment, check=True)
    if args.download_url:
        download_url = args.download_url
    else:
        download_url = next(line.strip() for line in (ROOT / "website/download-url.txt").read_text().splitlines()
                            if line.strip() and not line.lstrip().startswith("#"))

    output = ROOT / "dist"
    with tempfile.TemporaryDirectory(prefix="pod4me-web-", dir=output) as directory:
        stage = Path(directory) / "Pod4Me-web"
        public = stage / "public"
        public.mkdir(parents=True)
        # The archive is freshly generated; the old expanded preview may retain
        # stale files and is deliberately not used as the source of assets.
        with zipfile.ZipFile(output / "Pod4Me-website.zip") as website:
            for name in website.namelist():
                relative = Path(name)
                if relative.is_absolute() or ".." in relative.parts:
                    raise SystemExit("Unexpected unsafe path in generated sharing-site archive")
                target = public / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(website.read(name))
        shutil.copytree(app, public / "app")
        shutil.copyfile(ROOT / "webapp/worker.mjs", stage / "worker.mjs")
        shutil.copyfile(ROOT / "webapp/wrangler.jsonc", stage / "wrangler.jsonc")
        (stage / "deploy.sh").write_text(DEPLOY)
        (stage / "deploy.sh").chmod(0o755)
        (stage / "README.txt").write_text(instructions(download_url))
        association = json.loads((public / ".well-known/assetlinks.json").read_text())
        if association[0]["target"]["package_name"] != "com.pod4me":
            raise SystemExit("Wrong Android application association")
        archive = Path(directory) / "Pod4Me-web.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            for path in sorted(stage.rglob("*")):
                if path.is_file():
                    bundle.write(path, path.relative_to(stage).as_posix())
        preview = output / "Pod4Me-web"
        if preview.is_symlink():
            raise SystemExit("Refusing to replace a symlink at dist/Pod4Me-web")
        # Keep the deployment directory itself: the user's terminal may be
        # inside it. Removing it invalidates that shell's working directory.
        # Preserve local Wrangler cache/state, while replacing generated assets
        # so obsolete public files cannot be included in the next deployment.
        preview.mkdir(exist_ok=True)
        preview_public = preview / "public"
        if preview_public.is_symlink():
            raise SystemExit("Refusing to replace a symlink at dist/Pod4Me-web/public")
        if preview_public.exists():
            shutil.rmtree(preview_public)
        shutil.copytree(stage, preview, dirs_exist_ok=True)
        shutil.copyfile(archive, output / "Pod4Me-web.zip")
    digest = hashlib.sha256((output / "Pod4Me-web.zip").read_bytes()).hexdigest()
    (output / "Pod4Me-web.zip.sha256").write_text(f"{digest}  Pod4Me-web.zip\n")
    # Supersede the old helper's static-only deployment advice as well as the
    # README inside the new package. The current root page now links to /app/.
    (output / "Pod4Me-hosting-INSTRUCTIONS.txt").write_text(instructions(download_url))
    print(f"Created {output / 'Pod4Me-web.zip'} ({(output / 'Pod4Me-web.zip').stat().st_size:,} bytes)")
    print(f"Expanded package and exact instructions: {output / 'Pod4Me-web/README.txt'}")
    print("No deployment performed. Use this full package, not the older static-only website archive.")


if __name__ == "__main__":
    main()
