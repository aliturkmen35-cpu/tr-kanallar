"""iptv-org/api'deki kanalları world.json'a aktarır (--tr: yalnızca Türkiye + Türkçe yayınlar).

Kaynak: https://github.com/iptv-org/api (Unlicense). Akışlar iptv-org'un
topladığı herkese açık yayınlardır.

Dışarıda bırakılanlar:
  - iptv-org'un kendi engel listesi (telif şikâyeti / DMCA ve +18)
  - +18 işaretli, kapanmış ya da "xxx" kategorili kanallar
  (kanalı belirsiz akışlar başlığa göre aynı süzgeçten geçip "Diğer"e girer)
  - paralı spor/film platformları (beIN, S Sport, Sky, DAZN, HBO...): bunlar
    ücretsiz dağıtılmaz, listede görünüyorlarsa izinsiz yayındır
  - bilinen korsan sunucular
  - zaten channels.json'da (Alin's listesinde) olan kanallar

Kullanım:  python tools/import_iptv_org.py [--check] [--tr]   (repo kökünde world.json yazar)
"""
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API = "https://iptv-org.github.io/api/"
ROOT = Path(__file__).resolve().parent.parent

PAID = re.compile(
    r"\b(bein|s ?sport|digiturk|tivibu ?spor|exxen|tabii ?spor|sky ?(sport|cinema|movies|atlantic|showcase)|"
    r"dazn|hbo|cinemax|showtime|starz|espn|fox ?sports?|eurosport|movie ?smart|moviemax|"
    r"canal\+ ?(sport|cin)|setanta|arena ?sport|supersport|premier ?sports?|bt ?sport|tnt ?sports?|"
    r"sport ?tv ?\d|nba ?tv|nfl ?network|ufc|wwe|ppv|eleven ?sports?|viaplay|ziggo ?sport|"
    r"astro ?(supersport|arena)|star ?sports?|sony ?(ten|six|sports)|willow|ssc ?sport|ad ?sports?|"
    r"alkass|osn|shahid|movistar ?(deportes|laliga|liga)|m\+|max ?sport|polsat ?sport|"
    r"nova ?sport|match ?(tv|premier|futbol)|setanta|optus ?sport|kayo|stan ?sport|"
    # Paralı eğlence/çocuk/belgesel kanalları: ücretsiz resmi yayınları yok,
    # listede çıkanlar başka sağlayıcıların paketlerinden kopyalanmış
    r"disney|nick(elodeon|toons| ?jr\.?|/| schweiz)?|teennick|cartoon ?network|boomerang|cartoonito|"
    r"discovery|nat(ional)? ?geo(graphic)?|history ?\d?|viasat|animal ?planet|tlc|investigation discovery|"
    r"axn|warner ?(tv|bros)|amc|paramount ?(network|channel)|comedy ?central|fx|film ?box|epic ?drama|"
    r"sundance|e! ?entertainment|baby ?tv|cbeebies|cbbc|da vinci|duck ?tv|"
    r"sony ?(channel|max|pix|sab|entertainment|yay|aath|marathi|wah|bbc earth)|"
    r"star ?(plus|gold|bharat|movies|jalsha|pravah|vijay|maa|suvarna|world|life|utsav|channel)|"
    r"colors ?(tv|cineplex|bangla|marathi|kannada|tamil|gujarati|rishtey|infinity)?)\b",
    re.I,
)
BAD_HOSTS = ("mangeinde", "siteyaptim", "tv.vin", "streameast", "crichd", "hesgoal", "totalsportek",
             ".cfd/", "workers.dev", "stmify", "mangora")


def get(name):
    with urllib.request.urlopen(API + name + ".json", timeout=60) as r:
        return json.load(r)


def works(c):
    """Akış tamamen ölü mü? Bölgeye göre eleme YOK: coğrafi engel (401/403/451),
    yoğunluk (429/5xx) ya da zaman aşımı "çalışıyor" sayılır; çünkü başka bir
    ülkeden açılıyor olabilir. Yalnızca adresi olmayan (DNS), bağlantıyı reddeden
    ya da 404/410 veren akışlar elenir."""
    import socket
    import urllib.error
    dead = True
    for u in (c["url"], c.get("fallbackUrl")):
        if not u:
            continue
        try:
            h = {"User-Agent": "Mozilla/5.0", **c.get("headers", {})}
            with urllib.request.urlopen(urllib.request.Request(u, headers=h), timeout=12) as r:
                r.read(512)
            if u != c["url"]:
                c["url"] = u
                c.pop("fallbackUrl", None)
            return True
        except urllib.error.HTTPError as e:
            if e.code not in (404, 410):
                dead = False
        except urllib.error.URLError as e:
            r = e.reason
            if isinstance(r, (socket.timeout, TimeoutError)) or "timed out" in str(r):
                dead = False
            elif isinstance(r, socket.gaierror) or isinstance(r, ConnectionRefusedError):
                pass
            else:
                dead = False  # SSL vb. belirsiz hatalar: listede kalsın
        except Exception:
            dead = False
    return not dead


def main():
    channels = {c["id"]: c for c in get("channels")}
    feeds = {(f["channel"], f["id"]): f for f in get("feeds")}
    # Yurt dışı kanallarda yalnızca Türkçe yayın akışları (feed) alınır
    turkish = {k for k, f in feeds.items() if "tur" in f["languages"]}
    main_feed = {f["channel"]: f["id"] for f in feeds.values() if f["is_main"]}
    blocked = {b["channel"] for b in get("blocklist")}
    logos = {}
    for lg in get("logos"):
        if lg["feed"] is None and lg["channel"] not in logos:
            logos[lg["channel"]] = lg["url"]

    ours = json.loads((ROOT / "channels.json").read_text(encoding="utf-8"))
    have = {c.get("iptv", "").split("@")[0] for c in ours["channels"]}

    out, seen, skipped = {}, set(), {"blok": 0, "paralı": 0, "belirsiz": 0, "mevcut": 0, "kapalı": 0}
    for s in get("streams"):
        cid = s["channel"]
        url = s["url"]
        ch = channels.get(cid) if cid else None
        if ch is None:
            # Kanalı iptv-org'da tanımlı olmayan akışlar: başlığa göre süzülüp "Diğer"e girer
            title = (s.get("title") or "").strip()
            if not title or url in seen:
                continue
            if PAID.search(title) or any(h in url for h in BAD_HOSTS) or re.search(r"xxx|adult|porn|\+18|18\+", title, re.I):
                skipped["paralı"] += 1; continue
            seen.add(url)
            item = {"id": "w:u:" + url[-60:], "name": title, "country": "", "url": url}
            headers = {}
            if s.get("user_agent"):
                headers["User-Agent"] = s["user_agent"]
            if s.get("referrer"):
                headers["Referer"] = s["referrer"]
            if headers:
                item["headers"] = headers
            out[("u", url)] = item
            continue
        if cid in blocked or ch["is_nsfw"] or "xxx" in ch["categories"]:
            skipped["blok"] += 1; continue
        if ch["closed"]:
            skipped["kapalı"] += 1; continue
        if "--tr" in sys.argv and ch["country"] != "TR" and (cid, s["feed"] or main_feed.get(cid)) not in turkish:
            continue
        if cid in have:
            skipped["mevcut"] += 1; continue
        if PAID.search(ch["name"]) or PAID.search(s.get("title") or "") or any(h in url for h in BAD_HOSTS):
            skipped["paralı"] += 1; continue
        if url in seen:
            continue
        seen.add(url)

        key = (cid, s["feed"] or "")
        if key in out:  # aynı kanalın ikinci akışı yedek olur
            out[key].setdefault("fallbackUrl", url)
            continue
        name = ch["name"]
        feed = feeds.get((cid, s["feed"])) if s["feed"] else None
        if feed and not feed["is_main"]:
            name += f" ({feed['name']})"
        item = {"id": f"w:{cid}@{s['feed'] or ''}", "name": name, "country": ch["country"], "url": url}
        headers = {}
        if s.get("user_agent"):
            headers["User-Agent"] = s["user_agent"]
        if s.get("referrer"):
            headers["Referer"] = s["referrer"]
        if headers:
            item["headers"] = headers
        if ch["categories"]:
            item["genre"] = ch["categories"][0]
        if cid in logos:
            item["logo"] = logos[cid]
        out[key] = item

    from concurrent.futures import ThreadPoolExecutor
    cands = list(out.values())
    # Varsayılan: bölgeye göre eleme yok, hepsi listeye girer. --check ile açılmayanlar çıkarılır.
    if "--check" in sys.argv:
        with ThreadPoolExecutor(128) as ex:
            ok = list(ex.map(works, cands))
        skipped["açılmayan"] = ok.count(False)
    else:
        ok = [True] * len(cands)
    items = sorted((c for c, k in zip(cands, ok) if k), key=lambda c: (c["country"], c["name"].lower()))
    data = {
        "source": "https://github.com/iptv-org/api",
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "channels": items,
    }
    (ROOT / "world.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    countries = len({c["country"] for c in items})
    print(f"world.json: {len(items)} kanal, {countries} ülke. Atlanan: {skipped}", file=sys.stderr)


if __name__ == "__main__":
    main()
