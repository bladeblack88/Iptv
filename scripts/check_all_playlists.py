#!/usr/bin/env python3
"""Probe the existing IPTV playlists without rebuilding or changing their contents."""
import concurrent.futures
import re
import urllib.error
import urllib.parse
import urllib.request

FILES = ("working.m3u", "extra_channels.m3u")
TIMEOUT = 12
WORKERS = 24
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

def get(url, limit=8192):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Range": f"bytes=0-{limit-1}", "Accept": "*/*",
        "Icy-MetaData": "0",
    })
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = r.read(limit)
            return data, r.geturl(), r.headers.get("Content-Type", "").lower()
    except urllib.error.HTTPError as e:
        if e.code in (404, 410):
            raise Broken(f"HTTP {e.code}") from e
        raise Unverified(f"HTTP {e.code}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise Unverified(str(e)[:140]) from e

class Broken(Exception): pass
class Unverified(Exception): pass

def media_uri(text):
    lines = [x.strip() for x in text.splitlines() if x.strip()]
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            for candidate in lines[i+1:]:
                if not candidate.startswith("#"):
                    return candidate
    for line in lines:
        if not line.startswith("#"):
            return line
    return None

def probe(url, depth=0):
    if depth > 2:
        raise Broken("HLS nesting too deep")
    data, final_url, ctype = get(url)
    prefix = data.lstrip()[:160].lower()
    if "text/html" in ctype or prefix.startswith((b"<!doctype html", b"<html")):
        raise Broken("server returned HTML, not media")
    is_hls = ".m3u8" in urllib.parse.urlsplit(final_url).path.lower() or "mpegurl" in ctype or data.lstrip().startswith(b"#EXTM3U")
    if is_hls:
        text = data.decode("utf-8-sig", errors="replace")
        if not text.lstrip().startswith("#EXTM3U"):
            raise Broken("invalid HLS playlist")
        if "#EXTINF" not in text and "#EXT-X-STREAM-INF" not in text:
            raise Broken("playlist has no media entries")
        uri = media_uri(text)
        if not uri:
            raise Broken("playlist has no media URI")
        target = urllib.parse.urljoin(final_url, uri)
        sample, media_final, media_type = get(target, 4096)
        p = sample.lstrip()[:120].lower()
        if "text/html" in media_type or p.startswith((b"<!doctype html", b"<html")):
            raise Broken("media URI returned HTML")
        if ".m3u8" in urllib.parse.urlsplit(media_final).path.lower() or sample.lstrip().startswith(b"#EXTM3U"):
            probe(media_final, depth+1)
        elif len(sample) < 188:
            raise Broken("media segment too short")
    elif len(data) < 188:
        raise Broken("response too short")
    return True

def parse(path):
    entries, pending = [], None
    with open(path, encoding="utf-8-sig") as f:
        for raw in f:
            line = raw.strip()
            if not line or line == "#EXTM3U":
                continue
            if line.startswith("#EXTINF:"):
                pending = [line]
            elif line.startswith("#") and pending is not None:
                pending.append(line)
            elif re.match(r"^https?://", line, re.I) and pending is not None:
                title = pending[0].rsplit(",", 1)[-1].strip()
                entries.append((path, title, line))
                pending = None
    return entries

def check(entry):
    path, title, url = entry
    try:
        probe(url)
        return "OK", path, title, url, ""
    except Broken as e:
        return "BROKEN", path, title, url, str(e)
    except Unverified as e:
        return "UNVERIFIED", path, title, url, str(e)
    except Exception as e:
        return "UNVERIFIED", path, title, url, type(e).__name__ + ": " + str(e)[:100]

def main():
    entries = []
    for path in FILES:
        entries.extend(parse(path))
    unique, seen = [], set()
    for e in entries:
        key = e[2]
        if key not in seen:
            seen.add(key)
            unique.append(e)
    print(f"CHECK_START total={len(unique)}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = list(pool.map(check, unique))
    for status, path, title, url, detail in results:
        print(f"{status}\t{path}\t{title}\t{url}\t{detail}")
    counts = {s: sum(1 for r in results if r[0] == s) for s in ("OK", "BROKEN", "UNVERIFIED")}
    print("CHECK_SUMMARY " + " ".join(f"{k}={v}" for k,v in counts.items()))
    if counts["BROKEN"]:
        print("BROKEN_STREAMS_REQUIRE_REVIEW")
if __name__ == "__main__":
    main()
