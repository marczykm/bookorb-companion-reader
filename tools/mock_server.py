"""Minimal BookOrbit API stand-in for testing the Android app. Shapes follow the server's DTOs."""
import json, re, zlib, struct, zipfile, io, secrets, sys, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

def make_epub():
    lorem = ("The quick brown fox jumps over the lazy dog while the long afternoon light slides across the quiet valley. "
             "Nobody in the village could remember a summer so patient, and the river carried its stories downstream. ")
    def chap(n):
        def para(i):
            return " ".join(f"The quick brown fox {i}.{k} jumps over the lazy dog while the long afternoon light {i}.{k} slides across the quiet valley. "
                            f"Nobody in the village {i}.{k} could remember a summer so patient, and the river carried its stories downstream." for k in range(3))
        paras = "".join(f"<p>{para(i)} Paragraph {i} of chapter {n}.</p>" for i in range(1, 25))
        special = "<p id='special'>This unique sentence is the highlight target for testing.</p>" if n == 1 else ""
        return f"""<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter {n}</title></head>
<body><h1>Chapter {n}</h1>{special}{paras}</body></html>"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        z.writestr("OEBPS/content.opf", """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">mock-1</dc:identifier><dc:title>Mock Book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/><item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine></package>""")
        z.writestr("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">One</a></li><li><a href="c2.xhtml">Two</a></li><li><a href="c3.xhtml">Three</a></li></ol></nav></body></html>""")
        for n in (1, 2, 3): z.writestr(f"OEBPS/c{n}.xhtml", chap(n))
    return buf.getvalue()

def png(r, g, b, w=120, h=180):
    raw = b"".join(b"\x00" + bytes([r, g, min(255, b + y // 2)]) * w for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

EPUB = make_epub()
TITLES = [("Foxes of the Valley", "A. Writer", "Valley Saga", 1), ("Rivers Remember", "A. Writer", "Valley Saga", 2),
          ("The Patient Summer", "B. Novelist", None, None), ("Afternoon Light", "C. Poet", None, None),
          ("Stories Downstream", "B. Novelist", "River Tales", 1), ("Quiet Village", "C. Poet", "River Tales", 2)]
BOOKS = {}
for i, (t, a, s, idx) in enumerate(TITLES, 1):
    BOOKS[i] = dict(id=i, title=t, authors=[a], seriesName=s, files=[dict(id=100 + i, format="epub", role="primary")],
                    readingProgress=(35.0 if i == 1 else None), hasCover=True, rating=None,
                    readStatus=({"status": "reading"} if i == 1 else None))
PROGRESS = {101: 35.0}
ANN = {1: [dict(id=1, bookId=1, cfi="epubcfi(/6/2!/4/2/2,/1:0,/1:59)", text="This unique sentence is the highlight target for testing.", color="#4ADE80", style="highlight", note=None, origin="web")]}
NEXT_ANN = [10]
ACCESS = set(); REFRESH = {}; OFFLINE = False
LOG = open("requests.log", "a")

def log(*a): print(*a, file=LOG, flush=True); print(*a, flush=True)

def filt_status(body):
    f = (body or {}).get("filter") or {}
    for r in f.get("rules", []):
        if r.get("field") == "readStatus": return r["value"]
    return None

def page_of(items, body):
    pg = (body or {}).get("pagination", {"page": 0, "size": 50})
    s = pg["size"]; p = pg["page"]
    return dict(items=items[p * s:(p + 1) * s], total=len(items), page=p, size=s)

def query_books(body):
    q = ((body or {}).get("q") or "").lower(); st = filt_status(body)
    out = [b for b in BOOKS.values() if (not q or q in b["title"].lower()) and (not st or (b["readStatus"] or {"status": "unread"})["status"] in st)]
    return out

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body=None, ctype="application/json", headers=None):
        data = b"" if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(data)
    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try: return json.loads(raw) if raw else None
        except Exception: return None
    def authed(self):
        t = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
        return t in ACCESS
    def handle_any(self, method):
        u = urlparse(self.path); path = u.path; qs = parse_qs(u.query); body = self.body() if method in ("POST", "PATCH", "PUT") else None
        log(method, self.path, json.dumps(body) if body is not None else "")
        global OFFLINE
        if path.startswith("/__offline/"): OFFLINE = path.endswith("1"); return self.send(200, {"offline": OFFLINE})
        if OFFLINE and path.startswith("/api/"): self.close_connection = True; return  # behave like an unreachable server
        if path == "/__expire": ACCESS.clear(); return self.send(200, {"ok": True})
        if not path.startswith("/api/v1/"): return self.send(404, {"message": "nope"})
        p = path[len("/api/v1"):]
        if p == "/auth/login" and method == "POST":
            if not body or body.get("username") != "demo" or body.get("password") != "demo": return self.send(401, {"message": "Invalid"})
            assert body.get("clientKind") == "native", "clientKind must be native"
            return self.send(200, self.tokens())
        if p == "/auth/refresh" and method == "POST":
            rt = (body or {}).get("refreshToken")
            if rt not in REFRESH or not re.fullmatch(r"[a-f0-9]{64}", rt or ""): return self.send(401, {"message": "bad refresh"})
            del REFRESH[rt]; return self.send(200, self.tokens())
        if p == "/auth/logout": return self.send(200, {})
        if not self.authed(): return self.send(401, {"message": "Unauthorized"})
        if p == "/libraries": return self.send(200, [dict(id=1, name="Main"), dict(id=2, name="Poetry")])
        m = re.fullmatch(r"/libraries/(\d+)/books", p)
        if m and method == "POST":
            items = [b for b in query_books(body) if (int(m[1]) == 1) == (b["id"] % 2 == 1 or b["id"] < 4)]
            return self.send(200, page_of(items, body))
        if p == "/books/query" and method == "POST": return self.send(200, page_of(query_books(body), body))
        if p == "/dashboard/scrollers/batch":
            lists = {"continue-reading": [BOOKS[1]], "up-next-in-series": [BOOKS[2]], "want-to-read": [BOOKS[3]], "recently-added": list(BOOKS.values())}
            return self.send(200, {"items": [{"id": i["id"], "books": lists.get(i["type"], []), "failed": False} for i in body["items"]]})
        if p == "/dashboard/widgets/batch":
            today = datetime.date.today()
            data = {"reading-streak": {"currentStreak": 7, "longestStreak": 12, "lastSevenDays": [True, True, False, True, True, True, True]},
                    "reading-goal": {"goalBooks": 24, "completedBooks": 9, "year": today.year},
                    "reading-rhythm": {"days": [{"date": str(today - datetime.timedelta(days=6 - i)), "readingSeconds": s} for i, s in enumerate([600, 0, 1800, 2400, 900, 1200, 1500])], "consistencyPercent": 86, "avgSecondsPerDay": 1200, "activeDays": 6, "totalDays": 7},
                    "year-projection": {"projectedBooks": 18, "projectedPages": 5400, "projectedHours": 90, "booksCompletedYtd": 9, "daysRemaining": 60, "trend": "up"},
                    "monthly-challenge": {"challengeType": "short-read", "title": "Read 3 short books", "description": "Finish three books under 250 pages.", "progress": 1, "target": 3, "completed": False, "month": 11, "year": today.year},
                    "highlight-of-the-day": {"text": "The river carried its stories downstream.", "note": None, "bookTitle": "Foxes of the Valley", "bookId": 1, "hasCover": True, "chapterTitle": None, "createdAt": "2026-10-01T00:00:00Z"},
                    "library-overview": {"totalBooks": 6, "totalAuthors": 3, "totalSeries": 2, "totalStorageBytes": 1, "booksAddedThisYear": 6},
                    "long-wait": {"bookId": 4, "title": "Afternoon Light", "hasCover": True, "addedAt": "2025-01-01T00:00:00Z", "waitingDays": 300, "pageCount": 100, "genre": None, "fileId": 104, "fileFormat": "epub"}}
            return self.send(200, {"items": [{"type": t, "data": data.get(t), "failed": t not in data} for t in body["widgets"]]})
        m = re.fullmatch(r"/books/(\d+)/thumbnail", p)
        if m: i = int(m[1]); return self.send(200, png(40 + i * 30, 90 + i * 15, 160), "image/png")
        m = re.fullmatch(r"/books/files/(\d+)/serve", p)
        if m:
            rng = self.headers.get("Range")
            if rng:
                a, b = re.fullmatch(r"bytes=(\d*)-(\d*)", rng).groups(); a = int(a or 0); b = int(b) if b else len(EPUB) - 1
                return self.send(206, EPUB[a:b + 1], "application/epub+zip", {"Content-Range": f"bytes {a}-{b}/{len(EPUB)}", "Accept-Ranges": "bytes"})
            return self.send(200, EPUB, "application/epub+zip", {"Accept-Ranges": "bytes"})
        m = re.fullmatch(r"/books/files/(\d+)/progress", p)
        if m:
            fid = int(m[1])
            if method == "POST": assert 0 <= body["percentage"] <= 100; PROGRESS[fid] = body["percentage"]; return self.send(201, None)
            return self.send(200, {"percentage": PROGRESS.get(fid, 0), "cfi": None})
        m = re.fullmatch(r"/books/files/(\d+)/sessions", p)
        if m and method == "POST":
            for k in ("sessionId", "startedAt", "endedAt", "durationSeconds"): assert k in body, k
            assert body.get("source") == "android"; return self.send(204, None)
        m = re.fullmatch(r"/books/(\d+)/annotations(?:/(\d+))?", p)
        if m:
            bid = int(m[1]); lst = ANN.setdefault(bid, [])
            if method == "GET": return self.send(200, lst)
            if method == "POST":
                assert body["cfi"].startswith("epubcfi(") and body["text"], body
                a = dict(id=NEXT_ANN[0], bookId=bid, cfi=body["cfi"], text=body["text"], color=body.get("color", "yellow"), style=body.get("style", "highlight"), note=None, origin="web"); NEXT_ANN[0] += 1
                lst.append(a); return self.send(201, a)
            aid = int(m[2])
            if method == "PATCH":
                for a in lst:
                    if a["id"] == aid: a.update({k: v for k, v in body.items() if k in ("color", "style", "note")})
                return self.send(200, {})
            if method == "DELETE": ANN[bid] = [a for a in lst if a["id"] != aid]; return self.send(204, None)
        m = re.fullmatch(r"/books/(\d+)/status", p)
        if m and method == "PATCH": BOOKS[int(m[1])]["readStatus"] = {"status": body["status"]}; return self.send(200, {})
        if p == "/books/bulk-set-rating" and method == "POST":
            for i in body["bookIds"]: BOOKS[i]["rating"] = body.get("rating")
            return self.send(200, {})
        if p == "/series":
            ser = {}
            for b in BOOKS.values():
                if b["seriesName"]: ser.setdefault(b["seriesName"], []).append(b)
            items = [dict(id=i, name=n, bookCount=len(bs), readCount=0, authors=[bs[0]["authors"][0]], coverBookIds=[bs[0]["id"]]) for i, (n, bs) in enumerate(ser.items(), 1)]
            return self.send(200, dict(items=items, total=len(items), page=0, size=50))
        m = re.fullmatch(r"/series/(\d+)/books", p)
        if m: names = sorted({b["seriesName"] for b in BOOKS.values() if b["seriesName"]}); n = names[int(m[1]) - 1] if False else list({b["seriesName"]: 1 for b in BOOKS.values() if b["seriesName"]})[int(m[1]) - 1]; items = [b for b in BOOKS.values() if b["seriesName"] == n]; return self.send(200, dict(items=items, total=len(items), page=0, size=50))
        SCOPES = {1: ("Unread classics", [1, 3]), 2: ("Short reads", [2, 4, 5]), 3: ("Empty scope", [])}
        if p == "/smart-scopes":
            return self.send(200, [dict(id=i, name=n, bookCount=len(ids), userId=1, icon=None, filter=None, defaultSort=[], isPublic=False, isOwner=True) for i, (n, ids) in SCOPES.items()])
        m = re.fullmatch(r"/smart-scopes/(\d+)/books", p)
        if m:
            ids = SCOPES[int(m[1])][1]; items = [BOOKS[i] for i in ids]
            return self.send(200, dict(items=items, total=len(items), page=0, size=50))
        if p == "/authors":
            names = sorted({b["authors"][0] for b in BOOKS.values()})
            items = [dict(id=i, name=n, bookCount=sum(1 for b in BOOKS.values() if b["authors"][0] == n), coverBookId=next(b["id"] for b in BOOKS.values() if b["authors"][0] == n)) for i, n in enumerate(names, 1)]
            return self.send(200, dict(items=items, total=len(items), page=0, size=50))
        m = re.fullmatch(r"/authors/(\d+)/books", p)
        if m: n = sorted({b["authors"][0] for b in BOOKS.values()})[int(m[1]) - 1]; items = [b for b in BOOKS.values() if b["authors"][0] == n]; return self.send(200, dict(items=items, total=len(items), page=0, size=50))
        return self.send(404, {"message": "unhandled " + p})
    def tokens(self):
        a = "acc-" + secrets.token_hex(8); r = secrets.token_hex(32); ACCESS.add(a); REFRESH[r] = 1
        return dict(accessToken=a, accessTokenExpiresAt="2030-01-01T00:00:00Z", refreshToken=r, refreshTokenExpiresAt="2030-01-01T00:00:00Z", sessionId=1)
    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_PATCH(self): self.handle_any("PATCH")
    def do_DELETE(self): self.handle_any("DELETE")

if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 3000), H).serve_forever()
