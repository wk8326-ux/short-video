"""Local CMS/HLS fixture. Run on a disposable emulator with adb reverse tcp:18765 tcp:18765."""

import argparse
import json
import hashlib
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse, unquote


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--jar", type=Path, help="Generated CatVod test JAR")
    parser.add_argument("--drpy", type=Path, help="Optional directory with user-supplied drpy2.min.js and its relative modules")
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
                if parsed.path == '/drpy-config.json' and args.drpy:
                    return self.send_json({'sites': [{'key':'local-drpy', 'name':'drpy2 test', 'type':3,
                        'api':base+'/drpy/drpy2.min.js', 'ext':'./drpy-rule.js'}]})
                if parsed.path.startswith('/drpy/') and args.drpy:
                    name=unquote(parsed.path.removeprefix('/drpy/'))
                    if '/' not in name and '\\' not in name and (args.drpy/name).is_file():
                        return self.send_bytes((args.drpy/name).read_bytes(), 'application/javascript')
                    return self.send_error(404)
                if parsed.path == '/drpy-rule.js':
                    vod={'vod_id':base+'/film', 'vod_name':'drpy test film', 'vod_play_from':'fixture', 'vod_play_url':'Sample$'+base+'/protected/sample.m3u8'}
                    rule={'title':'drpy test', 'host':base, 'homeUrl':'/', 'url':'/films/fyclass/fypage', 'searchUrl':'/search?wd=**',
                        'class_name':'Test movies', 'class_url':'films', 'searchable':2, 'quickSearch':0, 'filterable':0,
                        'headers':{'User-Agent':'TVBox-fixture'}, '推荐':'*', '一级':'js:VODS='+json.dumps([vod])+';',
                        '二级':'js:VOD='+json.dumps(vod)+';', '搜索':'*', 'play_parse':True,
                        'lazy':'js:input='+json.dumps({'parse':0, 'url':base+'/protected/sample.m3u8','header':{'X-TVBox-Test':'fixture'}})+';'}
                    return self.send_bytes(('var rule='+json.dumps(rule,ensure_ascii=False)+';').encode(), 'application/javascript')
                if parsed.path in ("/js-config.json", "/jar-config.json", "/js-proxy-config.json", "/js-sniff-config.json", "/jar-proxy-config.json", "/global-js-config.json"):
                    is_jar = parsed.path.startswith('/jar')
                    payload = {"sites": [{"key": "local-jar" if is_jar else "local-js", "name": "JAR test" if is_jar else "JS test",
                        "type": 3, "api": "csp_Fixture" if is_jar else f"{base}/global-spider.js" if 'global' in parsed.path else f"{base}/spider.js", "ext": {"base": base, "mode": "proxy" if 'proxy' in parsed.path else 'sniff' if 'sniff' in parsed.path else 'direct'}}]}
                    if is_jar:
                        if not args.jar:
                            return self.send_error(404, "Start fixture with --jar")
                        payload["spider"] = f"{base}/fixture.jar;md5;{hashlib.md5(args.jar.read_bytes()).hexdigest()}"
                    return self.send_json(payload)
                if parsed.path == '/global-spider.js':
                    script="""__JS_SPIDER__={
                      init(ext){this.base=ext.base},
                      home(){return {class:[{type_id:'films',type_name:'Global scripts'}],list:[],total:1}},
                      homeVod(){return this.category()},
                      category(){return {list:[{vod_id:'film',vod_name:'Global script',vod_play_from:'fixture',vod_play_url:'Sample$opaque-token'}],page:1,pagecount:1,total:1}},
                      search(){return this.category()},detail(){return this.category()},
                      play(flag,id){if(id!=='opaque-token')throw Error('Token changed');return {parse:0,url:this.base+'/protected/sample.m3u8',header:{'X-TVBox-Test':'fixture'}}}
                    };"""
                    return self.send_bytes(script.encode(),'application/javascript')
                if parsed.path == '/watch.html':
                    return self.send_bytes(f'<video muted autoplay src="{base}/protected/sample.m3u8"></video>'.encode(), 'text/html')
                if parsed.path == "/fixture.jar" and args.jar:
                    return self.send_bytes(args.jar.read_bytes(), "application/java-archive")
                if parsed.path in ("/spider.js", "/helper.js"):
                    return self.send_bytes((Path(__file__).parent / 'fixtures' / parsed.path[1:]).read_bytes(), "application/javascript")
                if parsed.path == "/config.json":
                    return self.send_json({"sites": [{"key": "local-test", "name": "Local playback test",
                        "type": 1, "api": f"{base}/api.php/provide/vod/"}]})
                if parsed.path == "/api.php/provide/vod/":
                    result = {"code": 1, "total": 1, "page": 1, "pagecount": 1, "limit": 20, "list": [movie]}
                    if parse_qs(parsed.query).get("ac") == ["list"]:
                        result["class"] = [{"type_id": "films", "type_name": "Test movies"}]
                    return self.send_json(result)
                if parsed.path.startswith('/protected/'):
                    if self.headers.get('X-TVBox-Test') != 'fixture':
                        return self.send_error(403, 'Playback header missing')
                    filename = parsed.path.removeprefix('/protected/')
                else:
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
