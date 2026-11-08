#!/usr/bin/env python3
"""Build a shared M3U containing only streams that answer an HTTP request."""
import concurrent.futures
import os
import re
import sys
import urllib.error
import urllib.request

SOURCE = "https://iptv-org.github.io/iptv/countries/ru.m3u"
OUTPUT = "working.m3u"
TIMEOUT = 12
WORKERS = 20
USER_AGENT = "Mozilla/5.0 (compatible; IPTVPlaylistChecker/1.0)"

def fetch_source():
    request = urllib.request.Request(SOURCE, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read().decode("utf-8-sig", errors="replace")
    if "#EXTM3U" not in data[:500]:
        raise RuntimeError("Source did not return a valid M3U playlist")
    return data

def parse_entries(data):
    entries = []
    pending = []
    header = ["#EXTM3U"]
    for raw in data.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTM3U"):
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
    return header, entries

def is_working(entry):
    metadata, url = entry
    headers = {
        "User-Agent": USER_AGENT,
        "Range": "bytes=0-1023",
        "Icy-MetaData": "0",
    }
    try:
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            if not (200 <= response.status < 400):
                return False
            sample = response.read(1024)
            if not sample:
                return False
            content_type = response.headers.get("Content-Type", "").lower()
            prefix = sample.lstrip()[:100].lower()
            if "text/html" in content_type or prefix.startswith(b"<!doctype html") or prefix.startswith(b"<html"):
                return False
            if url.lower().split("?", 1)[0].endswith(".m3u8") and b"#extm3u" not in sample[:300].lower():
                return False
            return True
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return False

def main():
    source = fetch_source()
    header, entries = parse_entries(source)
    print(f"Found {len(entries)} source channels")
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = list(pool.map(is_working, entries))
    working = [entry for entry, ok in zip(entries, results) if ok]
    print(f"Working: {len(working)} / {len(entries)}")
    if not working:
        raise RuntimeError("No working streams detected; refusing to overwrite the last good playlist")
    lines = header[:]
    for metadata, url in working:
        lines.extend(metadata)
        lines.append(url)
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(lines) + "\n")
    print(f"Wrote {OUTPUT}")

if __name__ == "__main__":
    main()
