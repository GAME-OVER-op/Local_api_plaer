"""CI integration tests against the actual media-server binary; no external packages."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def main(binary):
    binary = str(Path(binary).resolve())
    with tempfile.TemporaryDirectory() as workspace:
        root = Path(workspace, "media")
        data = Path(workspace, "state")
        root.mkdir()
        data.mkdir()
        video = root / "01.mp4"
        video.write_bytes(b"episode-one-data")
        secret = "42" * 32
        (data / "allowed.json").write_text(json.dumps([
            {"id": "ci-tablet", "name": "CI tablet", "secret": secret}
        ]))
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        process = subprocess.Popen([binary, "-b", "-H", "127.0.0.1", "-p", str(port),
                                    "-r", str(root), "-d", str(data)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            def request(endpoint, path="", query="", version="", byte_range=None, query_version=False):
                params = {"path": path, "q": query}
                if query_version:
                    params["version"] = version
                ts, nonce = str(int(time.time())), os.urandom(16).hex()
                payload = "\n".join(["GET", endpoint, path, query, ts, nonce])
                headers = {"X-Device-Id": "ci-tablet", "X-Auth-Ts": ts, "X-Auth-Nonce": nonce,
                           "X-Auth-Sign": hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()}
                if version and not query_version:
                    headers["If-Match"] = '"' + version + '"'
                if byte_range:
                    headers["Range"] = byte_range
                url = f"http://127.0.0.1:{port}{endpoint}?" + urllib.parse.urlencode(params)
                req = urllib.request.Request(url, headers=headers)
                try:
                    response = urllib.request.urlopen(req, timeout=5)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.code, response.headers, response.read()

            for _ in range(50):
                try:
                    status, _, body = request("/file", "01.mp4")
                    if status == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.1)
            else:
                raise AssertionError("server did not start")
            info = json.loads(body)
            first_version = info["version"]
            assert len(first_version) == 64 and info["size"] == video.stat().st_size
            _, _, body = request("/list")
            listing = json.loads(body)
            assert listing["server_id"] == info["server_id"]
            assert listing["entries"][0]["version"] == first_version
            _, _, body = request("/search", query="01")
            assert json.loads(body)["entries"][0]["version"] == first_version
            status, headers, body = request("/download", "01.mp4", version=first_version, byte_range="bytes=2-5")
            assert status == 206 and body == b"isod", (status, body)
            assert headers["ETag"] == '"' + first_version + '"'
            status, _, body = request("/download", "01.mp4", version=first_version, query_version=True)
            assert status == 200 and body == b"episode-one-data"
            assert request("/download", "01.mp4", version="wrong-version")[0] == 412
            original_mtime = video.stat().st_mtime_ns
            # Same filename, same length and restored mtime: replacement must be a new version.
            replacement = root / "replacement"
            replacement.write_bytes(b"episode-two-data")
            os.utime(replacement, ns=(original_mtime, original_mtime))
            replacement.replace(video)
            _, _, body = request("/file", "01.mp4")
            second_version = json.loads(body)["version"]
            assert second_version != first_version
            assert request("/download", "01.mp4", version=first_version, byte_range="bytes=4-")[0] == 412
            status, _, body = request("/download", "01.mp4", version=second_version)
            assert status == 200 and body == b"episode-two-data"
            assert request("/download", "01.mp4", version=first_version, query_version=True)[0] == 412
            assert request("/file", "../outside")[0] == 400
            print("PASS: file metadata, listing/search identity, ranges, version guards and replacement")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main(sys.argv[1])
