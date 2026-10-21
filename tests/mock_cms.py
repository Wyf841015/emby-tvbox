#!/usr/bin/env python3
"""
本地 mock 苹果CMS（MacCMS v10）服务器 + TVBox 配置。

用途：给插件做端到端验证，不依赖任何外网源（外网采集站随时会挂，测不出真问题）。
同时提供:
  - /tvbox.json            TVBox 配置（两个站点：type0 = XML，type1 = JSON）
  - /xml/api.php/provide/vod/   type 0 的 XML 接口
  - /json/api.php/provide/vod/  type 1 的 JSON 接口
  - /hls/<id>/index.m3u8        播放列表（用于验证代理重写）
  - /hls/<id>/segN.ts           分片

启动: python3 mock_cms.py [port]
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from xml.sax.saxutils import escape

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
BASE = f"http://127.0.0.1:{PORT}"

# ─────────────────────── 假数据 ───────────────────────

CATEGORIES = [("1", "电影"), ("2", "电视剧")]

VODS = [
    {
        "id": "101", "name": "测试电影一", "cat": "1", "year": "2023",
        "pic": f"{BASE}/pic/101.jpg", "remarks": "HD",
        "lines": [("量子", [("正片", f"{BASE}/hls/101/index.m3u8")])],
    },
    {
        "id": "102", "name": "测试电影二", "cat": "1", "year": "2024",
        "pic": f"{BASE}/pic/102.jpg", "remarks": "HD",
        "lines": [("量子", [("正片", f"{BASE}/hls/102/index.m3u8")])],
    },
    {
        "id": "201", "name": "测试剧集", "cat": "2", "year": "2022",
        "pic": f"{BASE}/pic/201.jpg", "remarks": "更新至3集",
        "lines": [
            ("非凡", [("第1集", f"{BASE}/hls/201/e1.m3u8"),
                      ("第2集", f"{BASE}/hls/201/e2.m3u8"),
                      ("第3集", f"{BASE}/hls/201/e3.m3u8")]),
            ("量子", [("第1集", f"{BASE}/hls/201/q1.m3u8")]),
        ],
    },
    {
        # 只有 magnet 链接的条目：插件应该丢弃，不能生成空 strm
        "id": "103", "name": "只有磁力链的片子", "cat": "1", "year": "2020",
        "pic": "", "remarks": "",
        "lines": [("量子", [("正片", "magnet:?xt=urn:btih:deadbeef")])],
    },
]


def play_url(v):
    return "$$$".join(
        "#".join(f"{name}${url}" for name, url in eps) for _, eps in v["lines"]
    )


def play_from(v):
    return "$$$".join(name for name, _ in v["lines"])


# ─────────────────────── XML 接口 ───────────────────────

def xml_list(cat=None, page=1):
    items = [v for v in VODS if cat is None or v["cat"] == cat]
    videos = "".join(
        f"<video><vod_id>{v['id']}</vod_id><vod_name>{escape(v['name'])}</vod_name>"
        f"<vod_pic>{escape(v['pic'])}</vod_pic><vod_remarks>{escape(v['remarks'])}</vod_remarks>"
        f"<vod_year>{v['year']}</vod_year></video>"
        for v in items
    )
    classes = "".join(f'<ty id="{cid}">{escape(cname)}</ty>' for cid, cname in CATEGORIES)
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<rss version="5.1"><list page="1" pagecount="1" pagesize="20" '
        f'recordcount="{len(items)}">{videos}</list><class>{classes}</class></rss>'
    ).encode("utf-8")


def xml_detail(ids):
    items = [v for v in VODS if v["id"] in ids]
    videos = "".join(
        f"<video><vod_id>{v['id']}</vod_id><vod_name>{escape(v['name'])}</vod_name>"
        f"<vod_pic>{escape(v['pic'])}</vod_pic><vod_remarks>{escape(v['remarks'])}</vod_remarks>"
        f"<vod_year>{v['year']}</vod_year>"
        f"<vod_play_from>{escape(play_from(v))}</vod_play_from>"
        f"<vod_play_url>{escape(play_url(v))}</vod_play_url></video>"
        for v in items
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        f'<rss version="5.1"><list page="1" pagecount="1" pagesize="20" recordcount="{len(items)}">'
        f"{videos}</list><class></class></rss>"
    ).encode("utf-8")


# ─────────────────────── JSON 接口 ───────────────────────

def json_list(cat=None):
    items = [v for v in VODS if cat is None or v["cat"] == cat]
    return json.dumps({
        "code": 1, "msg": "数据列表", "page": 1, "pagecount": 1, "limit": "20",
        "total": len(items),
        "list": [{
            "vod_id": v["id"], "vod_name": v["name"], "vod_pic": v["pic"],
            "vod_remarks": v["remarks"], "vod_year": v["year"],
        } for v in items],
        "class": [{"type_id": cid, "type_name": cname} for cid, cname in CATEGORIES],
    }, ensure_ascii=False).encode("utf-8")


def json_detail(ids):
    items = [v for v in VODS if v["id"] in ids]
    return json.dumps({
        "code": 1, "msg": "数据列表", "page": 1, "pagecount": 1,
        "list": [{
            "vod_id": v["id"], "vod_name": v["name"], "vod_pic": v["pic"],
            "vod_remarks": v["remarks"], "vod_year": v["year"],
            "vod_play_from": play_from(v), "vod_play_url": play_url(v),
        } for v in items],
        "class": [],
    }, ensure_ascii=False).encode("utf-8")


TVBOX_CONFIG = {
    "sites": [
        {"key": "mock_xml", "name": "Mock XML 采集站", "type": 0,
         "api": f"{BASE}/xml/api.php/provide/vod/", "searchable": 1, "quickSearch": 1},
        {"key": "mock_json", "name": "Mock JSON 采集站", "type": 1,
         "api": f"{BASE}/json/api.php/provide/vod/", "searchable": 1, "quickSearch": 1},
        # spider 站点：插件必须识别为不支持
        {"key": "mock_spider", "name": "Mock Spider 站点", "type": 3,
         "api": "csp_Mock", "jar": "https://example.com/mock.jar"},
    ],
    "lives": [],
    "parses": [],
}

M3U8 = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4.000000,
seg0.ts
#EXTINF:4.000000,
seg1.ts
#EXT-X-ENDLIST
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] %s %s\n" % (self.address_string(), fmt % args))

    def _send(self, body, ctype="text/plain; charset=utf-8", status=200):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        path = u.path

        if path == "/tvbox.json":
            self._send(json.dumps(TVBOX_CONFIG, ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")
            return

        if path.startswith("/xml/api.php/provide/vod"):
            return self._cms(q, xml=True)
        if path.startswith("/json/api.php/provide/vod"):
            return self._cms(q, xml=False)

        if path.startswith("/hls/"):
            # 真实 HLS 文件（tests/hls 下由 ffmpeg 生成），验证真实播放链路
            import os
            name = os.path.basename(path)
            real = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hls", name)
            if os.path.isfile(real):
                ctype = ("application/vnd.apple.mpegurl" if name.endswith(".m3u8")
                         else "video/mp2t")
                with open(real, "rb") as fh:
                    self._send(fh.read(), ctype)
                return
        if path.startswith("/pic/"):
            # 1x1 gif
            self._send(b"GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff!"
                       b"\xf9\x04\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;",
                       "image/gif")
            return

        self._send(b"not found", status=404)

    def _cms(self, q, xml=True):
        ac = (q.get("ac") or [""])[0]
        t = (q.get("t") or [None])[0]
        ids = (q.get("ids") or [""])[0]
        id_list = [x for x in ids.split(",") if x]

        if ac == "detail" and id_list:
            body = xml_detail(id_list) if xml else json_detail(id_list)
        elif ac == "detail":
            body = xml_list(t) if xml else json_list(t)
        elif ac == "videolist":
            body = xml_list(t) if xml else json_list(t)
        else:  # ac=list
            body = xml_list(t, int((q.get("pg") or ["1"])[0])) if xml else json_list(t)

        ctype = "text/xml; charset=utf-8" if xml else "application/json; charset=utf-8"
        self._send(body, ctype)


if __name__ == "__main__":
    print(f"mock CMS 监听 http://127.0.0.1:{PORT}", flush=True)
    print(f"  TVBox 配置: {BASE}/tvbox.json", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
