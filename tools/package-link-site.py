#!/usr/bin/env python3
"""Prepare Cloudflare's static ZIP using the certificate of a verified local APK."""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "dist/Pod4Me.apk")
    parser.add_argument("--download-url", help="Public HTTPS APK URL; defaults to website/download-url.txt")
    args = parser.parse_args()
    if not args.download_url:
        configured = [line.strip() for line in (ROOT / "website/download-url.txt").read_text().splitlines()
                      if line.strip() and not line.lstrip().startswith("#")]
        if len(configured) != 1:
            parser.error("Set the public HTTPS APK URL in website/download-url.txt, or pass --download-url.")
        args.download_url = configured[0]
    address = urlsplit(args.download_url)
    if address.scheme != "https" or not address.hostname or address.username or address.password:
        parser.error("The APK download address must be HTTPS without credentials")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))) / "build-tools/35.0.0"
    certs = subprocess.check_output([str(sdk / "apksigner"), "verify", "--print-certs", str(args.apk)], text=True)
    digest = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})", certs)
    if not digest:
        raise SystemExit("Could not read the APK signing certificate")
    badging = subprocess.check_output([str(sdk / "aapt"), "dump", "badging", str(args.apk)], text=True)
    version = re.search(r"package: name='com.pod4me' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not version or "application-debuggable" in badging:
        raise SystemExit("Use a non-debuggable com.pod4me release APK")
    fingerprint = ":".join(re.findall("..", digest[1].upper()))
    association = [{"relation": ["delegate_permission/common.handle_all_urls"], "target": {
        "namespace": "android_app", "package_name": "com.pod4me", "sha256_cert_fingerprints": [fingerprint]}}]
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    # Temporary staging prevents stale files from silently entering a later ZIP.
    with tempfile.TemporaryDirectory(prefix="pod4me-site-", dir=output) as directory:
        stage = Path(directory)
        for name in ("index.html", "style.css", "link.js", "page.js", "404.html", "_headers"):
            data = (ROOT / "website" / name).read_text()
            if name == "index.html":
                data = data.replace("@@APK_DOWNLOAD_URL@@", html.escape(args.download_url, quote=True))
            (stage / name).write_text(data)
        (stage / ".well-known").mkdir()
        (stage / ".well-known/assetlinks.json").write_text(json.dumps(association, indent=2) + "\n")
        archive = output / "Pod4Me-website.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            for file in sorted(stage.rglob("*")):
                if file.is_file():
                    bundle.write(file, file.relative_to(stage).as_posix())
        preview = output / "Pod4Me-website"
        preview.mkdir(exist_ok=True)
        for file in stage.rglob("*"):
            if file.is_file():
                target = preview / file.relative_to(stage)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file, target)
    instructions = f"""POD4ME — FREE WEBSITE + ENS APK DOWNLOAD

The user requires no payment and no card number. R2 migration is cancelled.
Keep the existing Cloudflare website:
  https://pod4me.aurelien-garivier.workers.dev/
Its download button uses:
  {args.download_url}

UPLOAD THE APK
Upload dist/Pod4Me.apk into the ENS diffusion folder as Pod4Me.apk.
Keep this file/folder while the website points there. Other website files in
that folder are not needed by Pod4Me. Do not delete unrelated files blindly.
For later releases, replace Pod4Me.apk with the newly signed release.

WEBSITE
The deployed website already uses the ENS address above; no re-upload is
needed for restoring this arrangement. If changing the website later, upload
Pod4Me-website.zip to the existing pod4me Worker with files at the ZIP root,
including .well-known/assetlinks.json. Keep the Workers hostname unchanged.

VERIFY
Test the website download button and a fresh WhatsApp recommendation on the
phone. Android App Links may open Pod4Me directly; the browser page offers
Open in Pod4Me. Real phone routing remains to be tested.

SIGNING
Generated from verified Pod4Me {version[2]} (code {version[1]}).
Certificate SHA-256: {fingerprint}
Only the public fingerprint is uploaded, not the private key.
No APK rebuild, new subscription or R2 activation is needed for this setup.
"""
    (output / "Pod4Me-hosting-INSTRUCTIONS.txt").write_text(instructions)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / "Pod4Me-website.zip.sha256").write_text(f"{checksum}  Pod4Me-website.zip\n")
    print(f"Created {archive} ({archive.stat().st_size} bytes)")
    print(f"Certificate: {fingerprint}")
    print(f"Instructions: {output / 'Pod4Me-hosting-INSTRUCTIONS.txt'}")


if __name__ == "__main__":
    main()
