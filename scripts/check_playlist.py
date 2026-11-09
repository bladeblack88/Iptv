#!/usr/bin/env python3
"""Build a working IPTV playlist, verify streams, and keep the fastest duplicate."""
import concurrent.futures
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

SOURCE = "https://iptv-org.github.io/iptv/languages/rus.m3u"
OUTPUT = "working.m3u"
TIMEOUT = 15
WORKERS = 16
REPEAT_CHECKS = 2
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
EXTRA_CHANNELS = []
MIN_HD_HEIGHT = 720

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
    for i, line in enumerate(lines[:-1]):
        if line.startswith("#EXT-X-STREAM-INF:"):
            for candidate in lines[i + 1:]:
                if not candidate.startswith("#"):
                    return candidate
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

def probe_once(entry):
    _, url = entry
    data, final_url, content_type = request_bytes(url)
    if looks_like_hls(final_url, data, content_type):
        probe_hls(final_url, data)
    elif len(data) < 188:
        raise ValueError("media response is too short")
    return True

def check_entry(entry):
    timings = []
    try:
        for _ in range(REPEAT_CHECKS):
            started = time.monotonic()
            probe_once(entry)
            timings.append(time.monotonic() - started)
        # Both independent checks must pass. Lower average probe time wins
        # when several working URLs point to the same channel.
        return (True, sum(timings) / len(timings))
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, UnicodeError):
        return (False, float("inf"))

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

def is_hd(entry):
    """Keep only channels explicitly advertised as 720p or better."""
    metadata, _ = entry
    extinf = next((line for line in metadata if line.startswith("#EXTINF:")), "")
    # Prefer an explicit pixel height when available, so a 576p stream
    # isn't kept just because its channel name contains "HD".
    heights = re.findall(r"(?<!\d)(\d{3,4})\s*[pi]\b", extinf, flags=re.I)
    if heights:
        return max(map(int, heights)) >= MIN_HD_HEIGHT
    quality_text = " ".join(metadata)
    return bool(re.search(r"(?<![\w])(?:720p|1080[pi]?|1440p|2160p|4k|uhd|fhd|full\s*hd)(?![\w])", quality_text, flags=re.I))

def channel_key(entry):
    metadata, _ = entry
    extinf = next((line for line in metadata if line.startswith("#EXTINF:")), "")
    title = extinf.rsplit(",", 1)[-1] if "," in extinf else extinf
    title = re.sub(r"\([^)]*\)|\[[^]]*\]", " ", title)
    title = re.sub(r"\b(?:\d{3,4}p|\d{3,4}i|4k|uhd|fhd|hd|sd)\b", " ", title, flags=re.I)
    title = unicodedata.normalize("NFKC", title).casefold()
    title = re.sub(r"[^\w]+", "", title, flags=re.UNICODE)
    return title or extinf.casefold()

def main():
    source_entries = parse_entries(fetch_source())
    entries = [entry for entry in source_entries if is_hd(entry)]
    print(f"High-definition source channels (720p+): {len(entries)} of {len(source_entries)}")
    known_urls = {url.rstrip("/") for _, url in entries}
    for extra in EXTRA_CHANNELS:
        if extra[1].rstrip("/") not in known_urls:
            entries.append(extra)
            known_urls.add(extra[1].rstrip("/"))
    print(f"Checking {len(entries)} high-definition channels")

    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = list(pool.map(check_entry, entries))

    candidates = {}
    failed = 0
    for entry, (ok, latency) in zip(entries, results):
        if not ok:
            failed += 1
            continue
        key = channel_key(entry)
        previous = candidates.get(key)
        if previous is None or latency < previous[0]:
            candidates[key] = (latency, entry)

    working = [item[1] for item in candidates.values()]
    print(f"Stable streams: {len(working)}; failed checks: {failed}; duplicate alternatives removed: {sum(ok for ok, _ in results) - len(working)}")
    if not working:
        raise RuntimeError("No playable media endpoints detected; keeping previous playlist")

    # Keep source ordering, but only output the fastest verified URL per title.
    selected = {id(entry) for entry in working}
    lines = ["#EXTM3U"]
    for entry in entries:
        if id(entry) in selected:
            metadata, url = entry
            lines.extend(metadata)
            lines.append(url)
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(lines) + "\n")
    print(f"Wrote {OUTPUT}")

if __name__ == "__main__":
    main()
