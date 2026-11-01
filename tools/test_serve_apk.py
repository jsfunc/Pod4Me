"""Exercise the APK server's public file boundary in a disposable directory."""
import contextlib
import http.client
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time

with tempfile.TemporaryDirectory(prefix="pod4me-apk-server-") as temporary:
    root = Path(temporary)
    (root / "tools").mkdir()
    (root / "dist").mkdir()
    shutil.copyfile(Path(__file__).with_name("serve-apk.py"), root / "tools/serve-apk.py")
    (root / "dist/Pod4Me.apk").write_bytes(b"APK fixture")
    (root / "dist/Pod4Me.apk.sha256").write_text("checksum fixture")
    (root / "dist/private.txt").write_text("not public")
    (root / "outside.apk").write_text("outside")
    (root / "dist/link.apk").symlink_to(root / "outside.apk")
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    server = subprocess.Popen([sys.executable, str(root / "tools/serve-apk.py"), "--port", str(port)],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            if server.poll() is not None:
                raise RuntimeError("APK server could not start")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.02)
        for path, status in [("/Pod4Me.apk", 200), ("/Pod4Me.apk.sha256", 200), ("/", 404),
                             ("/private.txt", 404), ("/link.apk", 404), ("/../outside.apk", 404),
                             ("/%2e%2e/outside.apk", 404), ("/subdir/file.apk", 404)]:
            with contextlib.closing(http.client.HTTPConnection("127.0.0.1", port)) as client:
                client.request("GET", path)
                response = client.getresponse()
                assert response.status == status, (path, response.status)
                body = response.read()
                assert b"not public" not in body
        print("PASS: 8 APK server file-boundary checks")
    finally:
        server.terminate()
        server.wait(timeout=5)
