#!/usr/bin/env python3
"""Serve only dist/ so a phone on the same Wi-Fi can download the APK."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", type=int, default=8000)
args = parser.parse_args()
directory = Path(__file__).resolve().parents[1] / "dist"
if not (directory / "Pod4Me.apk").is_file():
    parser.error("Build the APK first with ./tools/build-apk.sh")

class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".apk": "application/vnd.android.package-archive"}

server = ThreadingHTTPServer(("0.0.0.0", args.port), partial(Handler, directory=str(directory)))
try:
    addresses = subprocess.check_output(["hostname", "-I"], text=True).split()
except (OSError, subprocess.CalledProcessError):
    addresses = []
print("Connect your phone to the same Wi-Fi, then open:", flush=True)
for address in addresses:
    if ":" not in address and not address.startswith("127."):
        print(f"  http://{address}:{args.port}/Pod4Me.apk", flush=True)
print(f"Listening on port {args.port}. Press Ctrl+C to stop.", flush=True)
try:
    server.serve_forever()
except KeyboardInterrupt:
    pass
finally:
    server.server_close()
