#!/usr/bin/env python3
"""Publish only streams whose media endpoint can actually be read."""
import concurrent.futures
import re
import urllib.error
import urllib.parse
import urllib.request

SOURCE = "https://iptv-org.github.io/iptv/countries/ru.m3u"
OUTPUT = "working.m3u"
TIMEOUT = 15
WORKERS = 16
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

def request_bytes(url, limit=8192):
    headers = {
        "User-Agent": USER_AGENT,
        "Range": f"bytes=0-{limit - 1}",
        "Icy-MetaData": "0",
        "Accept": "*/*",
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
        status = response.status
        content_type = response.headers.get("Content-Type", "").lower()
        data = response.read(limit)
        final_url = response.geturl()
    if not 200 <= status < 400 or not data:
        raise ValueError("empty or unsuccessful response")
    prefix = data.lstrip()[:160].lower()
    if "text/html" in content_type or prefix.startswith((b"<!doctype html", b"<html")):
        raise ValueError("server returned an HTML page")
    return data, final_url, content_type

def looks_like_hls(url, data, content_type):
    return (
        ".m3u8" in urllib.parse.urlsplit(url).path.lower()
        or "mpegurl" in content_type
        or data.lstrip().startswith(b"#EXTM3U")
    )

def first_media_uri(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    # For a master playlist, choose the first variant after EXT-X-STREAM-INF.
    for i, line in enumerate(lines[:-1]):
        if line.startswith("#EXT-X-STREAM-INF:"):
            for candidate in lines[i + 1:]:
                if not candidate.startswith("#"):
                    return candidate
    # For a media playlist, choose its first media segment.
    for line in lines:
        if not line.startswith("#"):
            return line
    return None

def probe_hls(url, playlist_data, depth=0):
    if depth > 2:
        raise ValueError("HLS nesting too deep")
    text = playlist_data.decode("utf-8-sig", errors="replace")
    if not text.lstrip().startswith("#EXTM3U"):
        raise ValueError("invalid HLS playlist")
    if "#EXTINF" not in text and "#EXT-X-STREAM-INF" not in text:
        raise ValueError("HLS playlist contains no media entries")
    media_uri = first_media_uri(text)
    if not media_uri:
        raise ValueError("HLS playlist has no media URI")
    media_url = urllib.parse.urljoin(url, media_uri)
    sample, final_url, content_type = request_bytes(media_url, 4096)
    if looks_like_hls(media_url, sample, content_type):
        probe_hls(final_url, sample, depth + 1)
    return True

def is_working(entry):
    _, url = entry
    try:
        data, final_url, content_type = request_bytes(url)
        if looks_like_hls(final_url, data, content_type):
            return probe_hls(final_url, data)
        # For direct TS/AAC/MP4 streams, a non-empty non-HTML media response is required.
        return len(data) >= 188
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, UnicodeError):
        return False

def fetch_source():
    data, _, _ = request_bytes(SOURCE, 2_000_000)
    text = data.decode("utf-8-sig", errors="replace")
    if "#EXTM3U" not in text[:500]:
        raise RuntimeError("Source did not return a valid M3U playlist")
    return text

def parse_entries(data):
    entries = []
    pending = []
    for raw in data.splitlines():
        line = raw.strip()
        if not line or line.startswith("#EXTM3U"):
            continue
        if line.startswith("#EXTINF:"):
            pending = [line]
        elif line.startswith("#"):
            if pending:
                pending.append(line)
        elif re.match(r"^https?://", line, re.I):
            if pending:
                entries.append((pending[:], line))
                pending = []
    return entries

def main():
    entries = parse_entries(fetch_source())
    print(f"Found {len(entries)} source channels")
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = list(pool.map(is_working, entries))
    working = [entry for entry, ok in zip(entries, results) if ok]
    print(f"Streams with readable media: {len(working)} / {len(entries)}")
    if not working:
        raise RuntimeError("No playable media endpoints detected; keeping previous playlist")
    lines = ["#EXTM3U"]
    for metadata, url in working:
        lines.extend(metadata)
        lines.append(url)
    with open(OUTPUT, "w", encoding="utf-8", newline="\\n") as file:
        file.write("\\n".join(lines) + "\\n")
    print(f"Wrote {OUTPUT}")

if __name__ == "__main__":
    main()
