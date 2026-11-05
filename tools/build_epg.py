"""Yayın akışı (EPG): Türk kanallarının program bilgilerini epg.json'a yazar.

Kaynak: epgshare01 (Türksat Kablo yayın akışından derlenen ücretsiz XMLTV).
Kanal adları Alin's kanallarıyla (channels.json + world.json'daki Türkiye) eşleştirilir.
Çıktı: {"updated":..., "epg": {"<kanal kimliği>": [[başlangıç, bitiş, "başlık"], ...]}}
Zamanlar UNIX saniyesidir; şimdiden itibaren 36 saat tutulur.

Kullanım:  python tools/build_epg.py
"""
import gzip
import json
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ["https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz"]
HOURS = 36


def fold(s):
    s = s.lower().replace("ı", "i").replace("İ", "i").replace("ş", "s").replace("ç", "c") \
        .replace("ğ", "g").replace("ö", "o").replace("ü", "u").replace("â", "a").replace("î", "i")
    s = re.sub(r"\b(hd|sd|fhd|uhd|4k|tv|televizyonu)\b", " ", s)
    return re.sub(r"[^a-z0-9]+", "", s)


def ts(v):
    return int(datetime.strptime(v, "%Y%m%d%H%M%S %z").timestamp())


def main():
    ours = json.loads((ROOT / "channels.json").read_text(encoding="utf-8"))["channels"]
    names = {}
    for c in ours:
        if c.get("type") != "radio":
            names.setdefault(fold(c["name"]), c["id"])
    wp = ROOT / "world.json"
    if wp.exists():
        for c in json.loads(wp.read_text(encoding="utf-8"))["channels"]:
            if c.get("country") == "TR":
                names.setdefault(fold(c["name"]), c["id"])

    now = int(time.time())
    end = now + HOURS * 3600
    epg, matched = {}, {}
    for src in SOURCES:
        req = urllib.request.Request(src, headers={"User-Agent": "alins-tv-epg"})
        with urllib.request.urlopen(req, timeout=120) as r:
            root = ET.fromstring(gzip.decompress(r.read()))
        idmap = {}
        for ch in root.iter("channel"):
            disp = ch.findtext("display-name") or ch.get("id")
            key = fold(disp)
            if key in names:
                idmap[ch.get("id")] = names[key]
                matched[disp] = names[key]
        for p in root.iter("programme"):
            cid = idmap.get(p.get("channel"))
            if not cid:
                continue
            try:
                a, b = ts(p.get("start")), ts(p.get("stop"))
            except Exception:
                continue
            if b < now or a > end:
                continue
            title = (p.findtext("title") or "").strip()
            if title:
                epg.setdefault(cid, []).append([a, b, title[:80]])
    # Aynı kanal birden çok kaynak kimliğinden (HD/SD) eşleşebilir: çakışan kayıtları ayıkla
    for cid, v in epg.items():
        v.sort()
        clean = []
        for p in v:
            if clean and p[0] < clean[-1][1]:
                continue  # öncekiyle çakışıyor (kopya)
            clean.append(p)
        epg[cid] = clean
    out = {"updated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"), "epg": epg}
    (ROOT / "epg.json").write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"epg.json: {len(epg)} kanal, {sum(len(v) for v in epg.values())} program", file=sys.stderr)
    print("Eşleşen:", ", ".join(sorted(matched)), file=sys.stderr)


if __name__ == "__main__":
    main()
