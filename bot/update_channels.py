"""
Kanal takip botu.

Her çalıştığında channels.json içindeki tüm kanalları kontrol eder:
  1. Kanalın "page" alanı varsa resmi canlı yayın sayfasından güncel yayın adresini okur.
  2. Yeni adresi ve mevcut adresi test eder, çalışanı listeye yazar.
  3. Sonuçları STATUS.md dosyasına yazar.

Bot hiçbir kanalı listeden silmez. GitHub sunucuları yurt dışında olduğu için bazı
kanallar yurt dışından erişimi engelleyebilir; bu durumda mevcut adres korunur.
Kullanım: python bot/update_channels.py channels.json STATUS.md
"""
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130 Safari/537.36")
DEFAULT_PATTERN = r"""https?:(?:\\?/){2}[^"'\s<>]+?\.m3u8[^"'\s<>]*"""


def fetch(url, headers=None, limit=None):
    h = {"User-Agent": UA}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=15) as r:
        data = r.read(limit) if limit else r.read()
        return r.status, data.decode("utf-8", "ignore")


def resolve(ch):
    """Resmi sayfadan güncel adresi okur. Bulamazsa None döner."""
    page = ch.get("page")
    if not page:
        return None
    try:
        _, html = fetch(page)
    except Exception as e:
        print(f"  sayfa okunamadı: {e}")
        return None
    m = re.search(ch.get("pattern") or DEFAULT_PATTERN, html)
    if not m:
        return None
    url = m.group(1) if m.groups() and m.group(1) else m.group(0)
    return url.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&")


def works(url, headers):
    if not url:
        return False
    try:
        status, body = fetch(url, headers, limit=64)
        return status == 200 and body.lstrip().startswith("#EXTM3U")
    except Exception:
        return False


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "channels.json"
    status_path = sys.argv[2] if len(sys.argv) > 2 else "STATUS.md"
    with open(path, encoding="utf-8") as f:
        data = json.load(f)

    rows, changed = [], False
    for ch in data["channels"]:
        name, headers, old = ch["name"], ch.get("headers", {}), ch.get("url", "")
        print(f"- {name}")
        if ch.get("resolveOnPlay"):
            # Adresi kısa süreli; uygulama her açılışta kendisi çözer. Burada yalnızca kontrol et.
            ok = works(resolve(ch), headers)
            rows.append((name, ok, "uygulama her açılışta güncel adresi okuyor"))
            continue

        new = resolve(ch)
        if new and new != old and works(new, headers):
            ch["url"] = new
            changed = True
            rows.append((name, True, "adres güncellendi"))
        elif works(old, headers):
            rows.append((name, True, ""))
        elif new and works(new, headers):
            ch["url"] = new
            changed = True
            rows.append((name, True, "adres güncellendi"))
        else:
            rows.append((name, False, "yurt dışından erişilemedi ya da yayın kapalı"))

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    if changed:
        data["version"] = int(data.get("version", 1)) + 1
        data["updated"] = now
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")

    ok_count = sum(1 for _, ok, _ in rows if ok)
    lines = [
        "# Kanal durumu",
        "",
        f"Son kontrol: {now} · {ok_count}/{len(rows)} kanal erişilebilir",
        "",
        "Kontrol GitHub'ın yurt dışındaki sunucularından yapılır. Burada kapalı görünen",
        "bir kanal Türkiye'den açılıyor olabilir.",
        "",
        "| Kanal | Durum | Not |",
        "|---|---|---|",
    ]
    lines += [f"| {n} | {'✅' if ok else '⚠️'} | {note} |" for n, ok, note in rows]
    with open(status_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"{ok_count}/{len(rows)} erişilebilir, değişiklik: {changed}")


if __name__ == "__main__":
    main()
