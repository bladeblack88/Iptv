#!/usr/bin/env python3
"""Conservatively remove only IPTV entries with repeatedly confirmed 404/410 responses."""
import concurrent.futures
import re
import time
import urllib.error
import urllib.parse
import urllib.request

PLAYLIST = "working.m3u"
TIMEOUT = 12
WORKERS = 12
ATTEMPTS = 3
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

def get_bytes(url, limit=65536):
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Icy-MetaData": "0",
        "Range": f"bytes=0-{limit - 1}",
    })
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            return response.status, response.read(limit), response.geturl(), response.headers.get("Content-Type", "").lower()
    except urllib.error.HTTPError as exc:
        return exc.code, b"", exc.geturl(), exc.headers.get("Content-Type", "").lower()

def first_media_uri(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            for candidate in lines[i + 1:]:
                if not candidate.startswith("#"):
                    return candidate
    for line in lines:
        if not line.startswith("#"):
            return line
    return None

def probe(url, depth=0):
    if depth > 3:
        return "unknown"
    status, data, final_url, content_type = get_bytes(url)
    if status in (404, 410):
        return "dead"
    if not 200 <= status < 400 or not data:
        return "unknown"
    prefix = data.lstrip()[:160].lower()
    if "text/html" in content_type or prefix.startswith((b"<!doctype html", b"<html")):
        return "unknown"
    is_hls = ".m3u8" in urllib.parse.urlsplit(final_url).path.lower() or "mpegurl" in content_type or data.lstrip().startswith(b"#EXTM3U")
    if not is_hls:
        return "working" if len(data) >= 188 else "unknown"
    text = data.decode("utf-8-sig", errors="replace")
    if not text.lstrip().startswith("#EXTM3U"):
        return "unknown"
    media_uri = first_media_uri(text)
    if not media_uri:
        return "unknown"
    child_url = urllib.parse.urljoin(final_url, media_uri)
    child_status, child_data, child_final, child_type = get_bytes(child_url, 4096)
    if child_status in (404, 410):
        return "dead"
    if not 200 <= child_status < 400 or not child_data:
        return "unknown"
    child_is_hls = ".m3u8" in urllib.parse.urlsplit(child_final).path.lower() or "mpegurl" in child_type or child_data.lstrip().startswith(b"#EXTM3U")
    if child_is_hls:
        return probe(child_final, depth + 1)
    return "working" if len(child_data) >= 188 else "unknown"

def check_entry(item):
    _, block = item
    text = "\n".join(block)
    # This channel is rendered through the KFU website player in the app.
    if re.search(r"univer\s*tv", text, re.I):
        return "preserve"
    match = next((re.match(r"^https?://\S+", line.strip(), re.I) for line in block if re.match(r"^https?://\S+", line.strip(), re.I)), None)
    if not match:
        return "preserve"
    url = match.group(0)
    results = []
    for attempt in range(ATTEMPTS):
        try:
            results.append(probe(url))
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            results.append("unknown")
        if attempt + 1 < ATTEMPTS:
            time.sleep(1)
    # Remove only when every independent attempt confirms a permanent missing resource.
    return "remove" if all(result == "dead" for result in results) else ("keep" if "working" in results else "unverified")

def main():
    with open(PLAYLIST, "r", encoding="utf-8-sig") as f:
        original = f.read()
    lines = original.splitlines()
    header = []
    blocks = []
    current = None
    for line in lines:
        if line.startswith("#EXTINF:"):
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is None:
            header.append(line)
        else:
            current.append(line)
    if current is not None:
        blocks.append(current)
    if not blocks:
        raise RuntimeError("No #EXTINF entries found; refusing to modify playlist")

    items = list(enumerate(blocks))
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        statuses = list(pool.map(check_entry, items))

    removed = sum(s == "remove" for s in statuses)
    kept = sum(s in ("keep", "preserve") for s in statuses)
    unverified = sum(s == "unverified" for s in statuses)
    if removed == len(blocks):
        raise RuntimeError("Every entry was marked for removal; refusing to empty playlist")

    output = list(header)
    for block, status in zip(blocks, statuses):
        if status != "remove":
            output.extend(block)
    new_content = "\n".join(output).rstrip() + "\n"
    if new_content != original:
        with open(PLAYLIST, "w", encoding="utf-8", newline="\n") as f:
            f.write(new_content)
    print(f"Playlist entries: {len(blocks)} | retained: {len(blocks)-removed} | removed (confirmed 404/410): {removed} | unverified/preserved: {unverified}")
    print("Playlist updated." if new_content != original else "No confirmed dead channels; playlist unchanged.")

if __name__ == "__main__":
    main()
