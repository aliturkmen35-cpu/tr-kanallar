"""Dünya kanallarını world.json'a aktarır.

Kaynaklar:
  - iptv-org/api (https://github.com/iptv-org/api, Unlicense): kanal bilgileri ve akışlar
  - iptv-org/iptv streams/*.m3u: kanalı tanımsız akışların hangi ülkeye ait olduğu
  - Free-TV/IPTV (https://github.com/Free-TV/IPTV): elle seçilmiş ücretsiz/yasal kanallar
  - sources/tr_playlist.m3u: kullanıcının Türkiye listesi. Bizde olan kanala yedek adres olur,
    olmayan kanal Türkiye'ye eklenir; Alin's kanallarında yedeği olmayana channels.json'da yedek yazılır.

Dışarıda bırakılanlar:
  - iptv-org'un engel listesi (DMCA ve +18), +18 ya da kapanmış kanallar
  - paralı spor/film/eğlence kanallarının izinsiz kopyaları (beIN, Disney, HBO...)
  - bilinen korsan sunucular
  - zaten channels.json'da (Alin's listesinde) olan kanallar
  - --check ile: alan adı dünyada hiç var olmayan (DNS kaydı yok) akışlar.
    Bölgeye göre eleme YOK: hangi ülkeden çalıştırılırsa çalıştırılsın sonuç aynıdır.

Kullanım:  python tools/import_iptv_org.py [--check] [--tr]
"""
import json
import re
import socket
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

API = "https://iptv-org.github.io/api/"
IPTV_STREAMS = "https://api.github.com/repos/iptv-org/iptv/contents/streams"
IPTV_RAW = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/"
FREETV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
ROOT = Path(__file__).resolve().parent.parent

PAID = re.compile(
    r"\b(bein|s ?sport|digiturk|tivibu ?spor|exxen|tabii ?spor|sky ?(sport|cinema|movies|atlantic|showcase)|"
    r"dazn|hbo|cinemax|showtime|starz|espn|fox ?sports?|eurosport|movie ?smart|moviemax|"
    r"canal\+ ?(sport|cin)|setanta|arena ?sport|supersport|premier ?sports?|bt ?sport|tnt ?sports?|"
    r"sport ?tv ?\d|nba ?tv|nfl ?network|ufc|wwe|ppv|eleven ?sports?|viaplay|ziggo ?sport|"
    r"astro ?(supersport|arena)|star ?sports?|sony ?(ten|six|sports)|willow|ssc ?sport|ad ?sports?|"
    r"alkass|osn|shahid|movistar ?(deportes|laliga|liga)|m\+|max ?sport|polsat ?sport|"
    r"nova ?sport|match ?(tv|premier|futbol)|optus ?sport|kayo|stan ?sport|"
    # Paralı eğlence/çocuk/belgesel kanalları: ücretsiz resmi yayınları yok,
    # listede çıkanlar başka sağlayıcıların paketlerinden kopyalanmış
    r"disney|nick(elodeon|toons| ?jr\.?|/| schweiz)?|teennick|cartoon ?network|boomerang|cartoonito|"
    r"discovery|nat(ional)? ?geo(graphic)?|history ?\d?|viasat|animal ?planet|tlc|investigation discovery|"
    r"axn|warner ?(tv|bros)|amc|paramount ?(network|channel)|comedy ?central|fx|film ?box|epic ?drama|"
    r"sundance|e! ?entertainment|baby ?tv|cbeebies|cbbc|da vinci|duck ?tv|"
    r"sony ?(channel|max|pix|sab|entertainment|yay|aath|marathi|wah|bbc earth)|"
    r"star ?(plus|gold|bharat|movies|jalsha|pravah|vijay|maa|suvarna|world|life|utsav|channel)|"
    r"colors ?(tv|cineplex|bangla|marathi|kannada|tamil|gujarati|rishtey|infinity)?|kanal ?d ?drama)\b",
    re.I,
)
ADULT = re.compile(r"xxx|adult|porn|\+18|18\+|erotic|hustler|playboy|brazzers", re.I)
BAD_HOSTS = ("mangeinde", "siteyaptim", "tv.vin", "streameast", "crichd", "hesgoal", "totalsportek",
             ".cfd/", "workers.dev", "stmify", "mangora", "iptvperu")


def fetch(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "alins-tv-importer"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def get(name):
    return json.loads(fetch(API + name + ".json"))


def parse_m3u(text):
    """M3U'yu (öznitelikler, başlık, adres, başlıklar) listesine çevirir."""
    out, attrs, title, headers = [], None, "", {}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            title = line.rsplit(",", 1)[-1].strip()
            headers = {}
        elif line.startswith("#EXTVLCOPT:http-user-agent="):
            headers["User-Agent"] = line.split("=", 1)[1]
        elif line.startswith("#EXTVLCOPT:http-referrer="):
            headers["Referer"] = line.split("=", 1)[1]
        elif line and not line.startswith("#") and attrs is not None:
            out.append((attrs, title, line, headers))
            attrs = None
    return out


def clean_title(t):
    # "NOW TV (720p) [Not 24/7]" -> "NOW TV"; Free-TV'nin Ⓖ / Ⓨ işaretleri de atılır
    t = re.sub(r"\s*\[[^\]]*\]", "", t)
    t = re.sub(r"\s*\((\d{3,4}[pi]|4K|HD|SD)\)", "", t)
    return re.sub(r"[ⒼⓎⓈ]", "", t).strip()


_dns = {}


def host_exists(url):
    host = urlparse(url).hostname or ""
    if host not in _dns:
        try:
            socket.getaddrinfo(host, None)
            _dns[host] = True
        except socket.gaierror:
            _dns[host] = False
        except Exception:
            _dns[host] = True
    return _dns[host]


def headers_of(ua, ref):
    h = {}
    if ua:
        h["User-Agent"] = ua
    if ref:
        h["Referer"] = ref
    return h


def main():
    channels = {c["id"]: c for c in get("channels")}
    feeds = {(f["channel"], f["id"]): f for f in get("feeds")}
    turkish = {k for k, f in feeds.items() if "tur" in f["languages"]}
    main_feed = {f["channel"]: f["id"] for f in feeds.values() if f["is_main"]}
    blocked = {b["channel"] for b in get("blocklist")}
    logos = {}
    for lg in get("logos"):
        if lg["feed"] is None and lg["channel"] not in logos:
            logos[lg["channel"]] = lg["url"]

    # Kanalı tanımsız akışların ülkesi: iptv-org/iptv'deki dosya adından (tr.m3u, tr_gem.m3u -> TR)
    url_country, repo_entries = {}, []
    files = [f["name"] for f in json.loads(fetch(IPTV_STREAMS)) if f["name"].endswith(".m3u")]

    def load_file(name):
        try:
            return name, parse_m3u(fetch(IPTV_RAW + name))
        except Exception:
            return name, []

    with ThreadPoolExecutor(16) as ex:
        for name, entries in ex.map(load_file, files):
            cc = re.split(r"[_.]", name)[0].upper()
            for attrs, title, url, h in entries:
                url_country.setdefault(url, cc)
                repo_entries.append((cc, attrs, title, url, h))

    ours = json.loads((ROOT / "channels.json").read_text(encoding="utf-8"))
    have = {c.get("iptv", "").split("@")[0] for c in ours["channels"]}
    have_urls = {c.get("url") for c in ours["channels"]} | {c.get("fallbackUrl") for c in ours["channels"]}

    out, seen = {}, set(have_urls)
    skipped = {"blok": 0, "paralı": 0, "mevcut": 0, "kapalı": 0}

    def bad(title, url):
        return PAID.search(title) or ADULT.search(title) or any(h in url for h in BAD_HOSTS)

    for s in get("streams"):
        cid, url = s["channel"], s["url"]
        ch = channels.get(cid) if cid else None
        if url in seen:
            continue
        if ch is None:
            title = clean_title(s.get("title") or "")
            if not title:
                continue
            if bad(title, url):
                skipped["paralı"] += 1; continue
            cc = url_country.get(url, "")
            if "--tr" in sys.argv and cc != "TR":
                continue
            seen.add(url)
            item = {"id": "w:u:" + url[-60:], "name": title, "country": cc, "url": url}
            if h := headers_of(s.get("user_agent"), s.get("referrer")):
                item["headers"] = h
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
        if bad(ch["name"], url) or PAID.search(s.get("title") or ""):
            skipped["paralı"] += 1; continue
        seen.add(url)

        key = (cid, s["feed"] or "")
        if key in out:  # aynı kanalın diğer akışları: sonda yaşayan ilk ikisi ana adres + yedek olur
            out[key]["_alts"].append((url, headers_of(s.get("user_agent"), s.get("referrer"))))
            continue
        name = ch["name"]
        feed = feeds.get((cid, s["feed"])) if s["feed"] else None
        if feed and not feed["is_main"]:
            name += f" ({feed['name']})"
        item = {"id": f"w:{cid}@{s['feed'] or ''}", "name": name, "country": ch["country"], "url": url, "_alts": []}
        if h := headers_of(s.get("user_agent"), s.get("referrer")):
            item["headers"] = h
        if ch["categories"]:
            item["genre"] = ch["categories"][0]
        if cid in logos:
            item["logo"] = logos[cid]
        out[key] = item

    # iptv-org/iptv deposunda olup API'ye (henüz) düşmemiş ya da API'de eşleşmeyen kanallar
    repo_new = 0
    known_names = {(c["country"], c["name"].lower()) for c in out.values()}
    have_cid = {c["id"][2:].split("@")[0] for c in out.values() if "@" in c["id"]}
    for cc, attrs, title, url, h in repo_entries:
        title = clean_title(title)
        cid = (attrs.get("tvg-id") or "").split("@")[0]
        ch = channels.get(cid)
        if url in seen or not title or bad(title, url) or cid in have or cid in have_cid:
            continue
        if ch and (cid in blocked or ch["is_nsfw"] or ch["closed"]):
            continue
        if "--tr" in sys.argv and cc != "TR":
            continue
        if (cc, title.lower()) in known_names:
            continue
        seen.add(url)
        known_names.add((cc, title.lower()))
        if cid:
            have_cid.add(cid)
        item = {"id": "w:r:" + (cid or url[-60:]), "name": title, "country": cc, "url": url}
        if h:
            item["headers"] = h
        if ch and ch["categories"]:
            item["genre"] = ch["categories"][0]
        if cid in logos:
            item["logo"] = logos[cid]
        out[("r", url)] = item
        repo_new += 1

    # Free-TV: iptv-org'da olmayan ücretsiz kanallar
    freetv = 0
    for attrs, title, url, h in parse_m3u(fetch(FREETV)):
        title = clean_title(title)
        cc = (attrs.get("tvg-country") or "").upper()[:2]
        if url in seen or not title or bad(title, url):
            continue
        if "--tr" in sys.argv and cc != "TR":
            continue
        if attrs.get("tvg-id") in have or (cc, title.lower()) in known_names:
            continue
        seen.add(url)
        item = {"id": "w:f:" + url[-60:], "name": title, "country": cc, "url": url}
        if h:
            item["headers"] = h
        if attrs.get("tvg-logo"):
            item["logo"] = attrs["tvg-logo"]
        out[("f", url)] = item
        freetv += 1

    # Kullanıcının Türkiye listesi (sources/tr_playlist.m3u)
    local, extra_fb, curated_fb = 0, 0, 0
    lp = ROOT / "sources" / "tr_playlist.m3u"
    if lp.exists():
        by_cid = {}
        for k, c in out.items():
            if c["id"].startswith("w:") and "@" in c["id"] and not c["id"].startswith(("w:u:", "w:f:")):
                by_cid.setdefault(c["id"][2:].split("@")[0], c)
        curated = {c.get("iptv", "").split("@")[0]: c for c in ours["channels"] if c.get("iptv")}
        for attrs, title, url, h in parse_m3u(lp.read_text(encoding="utf-8")):
            title = clean_title(title)
            cid = (attrs.get("tvg-id") or "").split("@")[0]
            if not title or bad(title, url):
                continue
            if cid in curated:
                c = curated[cid]
                if url not in (c.get("url"), c.get("fallbackUrl")) and not c.get("fallbackUrl") and not c.get("stayOriginal"):
                    c["fallbackUrl"] = url
                    curated_fb += 1
                continue
            if url in seen:
                continue
            seen.add(url)
            if cid in by_cid:
                c = by_cid[cid]
                if not c.get("fallbackUrl") and c["url"] != url:
                    c["fallbackUrl"] = url
                    extra_fb += 1
                continue
            item = {"id": "w:p:" + (cid or url[-60:]), "name": title, "country": "TR", "url": url}
            if h:
                item["headers"] = h
            if attrs.get("tvg-logo"):
                item["logo"] = attrs["tvg-logo"]
            out[("p", url)] = item
            if cid:
                by_cid[cid] = item
            local += 1
        if curated_fb:
            (ROOT / "channels.json").write_text(json.dumps(ours, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Türkiye listesi: {local} yeni kanal, {extra_fb} dünya + {curated_fb} Alin's kanalına yedek adres",
          file=sys.stderr)

    check = "--check" in sys.argv

    def settle(c):
        """Kanalın tüm akışlarından alan adı var olan ilk ikisini ana adres + yedek yapar."""
        alts = [(c["url"], c.get("headers") or {})]
        if c.get("fallbackUrl"):
            alts.append((c["fallbackUrl"], {}))
        alts += c.pop("_alts", [])
        live = [a for a in alts if host_exists(a[0])] if check else alts
        if not live:
            return False
        c["url"], h = live[0]
        c.pop("headers", None)
        if h:
            c["headers"] = h
        c.pop("fallbackUrl", None)
        if len(live) > 1:
            c["fallbackUrl"] = live[1][0]
        return True

    cands = list(out.values())
    with ThreadPoolExecutor(64) as ex:
        ok = list(ex.map(settle, cands))
    if check:
        skipped["alan adı yok"] = ok.count(False)
    items = sorted((c for c, k in zip(cands, ok) if k), key=lambda c: (c["country"], c["name"].lower()))
    data = {
        "source": "iptv-org/api + iptv-org/iptv (streams) + Free-TV/IPTV",
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "channels": items,
    }
    (ROOT / "world.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tr = sum(1 for c in items if c["country"] == "TR")
    countries = len({c["country"] for c in items})
    print(f"world.json: {len(items)} kanal ({tr} Türkiye), {countries} ülke, Free-TV'den {freetv}, iptv-org deposundan ek {repo_new}. Atlanan: {skipped}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
