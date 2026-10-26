"""Local CMS/HLS fixture. Run on a disposable emulator with adb reverse tcp:18765 tcp:18765."""

import argparse
import json
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    with tempfile.TemporaryDirectory(prefix="tvbox-device-test-") as temporary:
        media = Path(temporary)
        subprocess.run([
            "ffmpeg", "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
            "-t", "32", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
            "-preset", "ultrafast", "-g", "100", "-keyint_min", "100", "-sc_threshold", "0",
            "-c:a", "aac", "-b:a", "96k", "-f", "hls", "-hls_time", "4", "-hls_list_size", "0",
            "-hls_segment_filename", str(media / "sample-%d.ts"), str(media / "sample.m3u8"),
        ], check=True)
        movie = {
            "vod_id": "test-1", "vod_name": "Local TVBox playback test", "vod_year": "2026",
            "vod_content": "Local H.264 playback sample", "vod_play_from": "Direct HLS",
            "vod_play_url": f"Sample${base}/sample.m3u8",
        }

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                parsed = urlparse(self.path)
                if parsed.path == "/config.json":
                    return self.send_json({"sites": [{"key": "local-test", "name": "Local playback test",
                        "type": 1, "api": f"{base}/api.php/provide/vod/"}]})
                if parsed.path == "/api.php/provide/vod/":
                    result = {"code": 1, "total": 1, "page": 1, "pagecount": 1, "limit": 20, "list": [movie]}
                    if parse_qs(parsed.query).get("ac") == ["list"]:
                        result["class"] = [{"type_id": "films", "type_name": "Test movies"}]
                    return self.send_json(result)
                filename = parsed.path.removeprefix("/")
                valid_segment = filename.startswith("sample-") and filename.endswith(".ts") and filename[7:-3].isdigit()
                if filename == "sample.m3u8" or valid_segment:
                    file = media / filename
                    if file.is_file():
                        mime = "application/vnd.apple.mpegurl" if filename.endswith(".m3u8") else "video/mp2t"
                        return self.send_bytes(file.read_bytes(), mime)
                self.send_error(404)

            def send_json(self, payload):
                self.send_bytes(json.dumps(payload).encode(), "application/json; charset=utf-8")

            def send_bytes(self, payload, mime):
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        print(f"Fixture ready: {base}/config.json", flush=True)
        ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
